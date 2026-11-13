#!/usr/bin/env python3
"""Paced OTLP/gRPC trace generator with latency and schedule-lag reporting."""

import argparse
import asyncio
import hashlib
import json
import math
import time
from dataclasses import dataclass, field

import grpc
from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import (
    ExportTraceServiceRequest,
)
from opentelemetry.proto.collector.trace.v1.trace_service_pb2_grpc import (
    TraceServiceStub,
)
from opentelemetry.proto.common.v1.common_pb2 import AnyValue, InstrumentationScope, KeyValue
from opentelemetry.proto.resource.v1.resource_pb2 import Resource
from opentelemetry.proto.trace.v1.trace_pb2 import ResourceSpans, ScopeSpans, Span


@dataclass
class Counters:
    attempted: int = 0
    accepted: int = 0
    rejected: int = 0
    requests: int = 0
    failed_requests: int = 0
    latencies_ms: list[float] = field(default_factory=list)
    schedule_lags_ms: list[float] = field(default_factory=list)
    status_codes: dict[str, int] = field(default_factory=dict)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--endpoint",
        action="append",
        dest="endpoints",
        help=("OTLP/gRPC endpoint; repeat to distribute workers across receivers "
              "(default: host.docker.internal:4317)"),
    )
    parser.add_argument("--rate", type=float, required=True, help="Target spans per second")
    parser.add_argument("--duration", type=float, required=True, help="Generation duration in seconds")
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--timeout", type=float, default=30.0, help="RPC timeout in seconds")
    parser.add_argument("--run-id", default="benchmark")
    args = parser.parse_args()
    if not args.endpoints:
        args.endpoints = ["host.docker.internal:4317"]
    if args.rate <= 0 or args.duration <= 0 or args.batch_size <= 0 or args.concurrency <= 0:
        parser.error("rate, duration, batch-size, and concurrency must be positive")
    if args.batch_size % 2 != 0:
        parser.error("batch-size must be even so every client span has a server peer")
    return args


def deterministic_bytes(run_id: str, sequence: int, suffix: str, length: int) -> bytes:
    value = f"{run_id}:{sequence}:{suffix}".encode()
    return hashlib.blake2b(value, digest_size=length).digest()


def request_for(run_id: str, first_sequence: int, count: int) -> ExportTraceServiceRequest:
    now = time.time_ns()
    frontend_spans = []
    backend_spans = []
    for offset in range(count):
        sequence = first_sequence + offset
        trace_sequence = sequence // 2
        trace_id = deterministic_bytes(run_id, trace_sequence, "trace", 16)
        client_span_id = deterministic_bytes(run_id, trace_sequence, "client", 8)
        trace_start = now + (offset // 2) * 2_000_000
        attributes = [KeyValue(
            key="benchmark.sequence",
            value=AnyValue(int_value=sequence),
        )]
        if sequence % 2 == 0:
            frontend_spans.append(Span(
                trace_id=trace_id,
                span_id=client_span_id,
                name="call-backend",
                kind=Span.SPAN_KIND_CLIENT,
                start_time_unix_nano=trace_start,
                end_time_unix_nano=trace_start + 2_000_000,
                attributes=attributes,
            ))
        else:
            backend_spans.append(Span(
                trace_id=trace_id,
                span_id=deterministic_bytes(run_id, trace_sequence, "server", 8),
                parent_span_id=client_span_id,
                name="GET /work",
                kind=Span.SPAN_KIND_SERVER,
                start_time_unix_nano=trace_start + 500_000,
                end_time_unix_nano=trace_start + 1_500_000,
                attributes=attributes,
            ))

    scope = InstrumentationScope(name="opensearch-otlp-benchmark", version="1")

    def resource_spans(service_name: str, spans: list[Span]) -> ResourceSpans:
        resource = Resource(attributes=[
            KeyValue(key="service.name", value=AnyValue(string_value=service_name)),
            KeyValue(key="benchmark.run_id", value=AnyValue(string_value=run_id)),
        ])
        return ResourceSpans(
            resource=resource,
            scope_spans=[ScopeSpans(scope=scope, spans=spans)],
        )

    return ExportTraceServiceRequest(resource_spans=[
        resource_spans("benchmark-frontend", frontend_spans),
        resource_spans("benchmark-backend", backend_spans),
    ])


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(fraction * len(ordered)) - 1)
    return round(ordered[rank], 3)


async def run_worker(
    worker_id: int,
    args: argparse.Namespace,
    start: float,
    deadline: float,
    counters: Counters,
) -> None:
    endpoint = args.endpoints[worker_id % len(args.endpoints)]
    channel = grpc.aio.insecure_channel(endpoint)
    stub = TraceServiceStub(channel)
    period = args.batch_size * args.concurrency / args.rate
    next_send = start + worker_id * period / args.concurrency
    batch_number = 0

    try:
        while next_send < deadline:
            delay = next_send - time.perf_counter()
            if delay > 0:
                await asyncio.sleep(delay)
            now = time.perf_counter()
            if now >= deadline:
                break
            counters.schedule_lags_ms.append(max(0, (now - next_send) * 1000))
            sequence = worker_id * 1_000_000_000_000 + batch_number * args.batch_size
            request = request_for(args.run_id, sequence, args.batch_size)
            counters.attempted += args.batch_size
            counters.requests += 1
            request_start = time.perf_counter()
            try:
                response = await stub.Export(request, timeout=args.timeout)
                elapsed_ms = (time.perf_counter() - request_start) * 1000
                counters.latencies_ms.append(elapsed_ms)
                rejected = response.partial_success.rejected_spans
                counters.rejected += rejected
                counters.accepted += args.batch_size - rejected
            except grpc.aio.AioRpcError as error:
                counters.failed_requests += 1
                code = error.code().name
                counters.status_codes[code] = counters.status_codes.get(code, 0) + 1
            batch_number += 1
            next_send += period
    finally:
        await channel.close()


async def main() -> None:
    args = parse_args()
    counters = Counters()
    started = time.perf_counter()
    deadline = started + args.duration
    await asyncio.gather(*(
        run_worker(worker, args, started, deadline, counters)
        for worker in range(args.concurrency)
    ))
    # Keep the process alive until the requested boundary so throughput uses the
    # complete fixed-duration offered-load window.
    remaining = deadline - time.perf_counter()
    if remaining > 0:
        await asyncio.sleep(remaining)
    elapsed = time.perf_counter() - started

    offered_records = round(args.rate * args.duration)
    result = {
        "run_id": args.run_id,
        "target_rate": args.rate,
        "duration_seconds": round(elapsed, 3),
        "batch_size": args.batch_size,
        "concurrency": args.concurrency,
        "endpoints": args.endpoints,
        "attempted_records": counters.attempted,
        "offered_records": offered_records,
        "unsent_records": max(0, offered_records - counters.attempted),
        "accepted_records": counters.accepted,
        "rejected_records": counters.rejected,
        "requests": counters.requests,
        "failed_requests": counters.failed_requests,
        "status_codes": counters.status_codes,
        "accepted_records_per_second": round(counters.accepted / elapsed, 3),
        "request_latency_ms": {
            "p50": percentile(counters.latencies_ms, 0.50),
            "p95": percentile(counters.latencies_ms, 0.95),
            "p99": percentile(counters.latencies_ms, 0.99),
            "max": round(max(counters.latencies_ms), 3) if counters.latencies_ms else None,
        },
        "schedule_lag_ms": {
            "p50": percentile(counters.schedule_lags_ms, 0.50),
            "p95": percentile(counters.schedule_lags_ms, 0.95),
            "p99": percentile(counters.schedule_lags_ms, 0.99),
            "max": round(max(counters.schedule_lags_ms), 3) if counters.schedule_lags_ms else None,
        },
    }
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
