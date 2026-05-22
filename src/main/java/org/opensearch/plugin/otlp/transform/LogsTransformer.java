/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to this file be
 * licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.opensearch.plugin.otlp.transform;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import org.opensearch.plugin.otlp.document.LogDocument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LogsTransformer {

    public List<LogDocument> transform(ExportLogsServiceRequest request) {
        List<LogDocument> docs = new ArrayList<>();
        for (ResourceLogs rl : request.getResourceLogsList()) {

            for (ScopeLogs sl : rl.getScopeLogsList()) {
                for (LogRecord log : sl.getLogRecordsList()) {
                    docs.add(new LogDocument(rl.getResource(), rl.getSchemaUrl(), sl.getScope(), sl.getSchemaUrl(), log));
                }
            }
        }
        return docs;
    }
}
