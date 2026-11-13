#!/usr/bin/env python3
"""Summarize total container CPU and memory samples."""

import csv
import json
import re
import sys
from collections import defaultdict


UNITS = {
    "B": 1, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3,
    "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3,
}


def bytes_from_usage(value: str) -> float:
    usage = value.split("/", 1)[0].strip()
    match = re.fullmatch(r"([0-9.]+)([A-Za-z]+)", usage)
    if not match:
        return 0.0
    return float(match.group(1)) * UNITS.get(match.group(2), 0)


samples: dict[str, dict[str, float]] = defaultdict(lambda: {"cpu": 0.0, "memory": 0.0})
with open(sys.argv[1], newline="", encoding="utf-8") as stream:
    for row in csv.DictReader(stream):
        timestamp = row["timestamp"]
        samples[timestamp]["cpu"] += float(row["cpu_percent"].rstrip("%"))
        samples[timestamp]["memory"] += bytes_from_usage(row["memory_usage"])

cpu = [sample["cpu"] for sample in samples.values()]
memory = [sample["memory"] / 1024 / 1024 for sample in samples.values()]
result = {
    "sample_count": len(samples),
    "average_total_cpu_percent": round(sum(cpu) / len(cpu), 3) if cpu else 0,
    "peak_total_cpu_percent": round(max(cpu), 3) if cpu else 0,
    "average_total_memory_mib": round(sum(memory) / len(memory), 3) if memory else 0,
    "peak_total_memory_mib": round(max(memory), 3) if memory else 0,
}
print(json.dumps(result, sort_keys=True))
