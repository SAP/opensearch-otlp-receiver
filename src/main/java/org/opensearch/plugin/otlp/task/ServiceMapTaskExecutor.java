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
package org.opensearch.plugin.otlp.task;

import org.opensearch.common.Nullable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.tasks.TaskId;
import org.opensearch.persistent.AllocatedPersistentTask;
import org.opensearch.persistent.PersistentTaskState;
import org.opensearch.persistent.PersistentTasksCustomMetadata.PersistentTask;
import org.opensearch.persistent.PersistentTasksExecutor;
import org.opensearch.plugin.otlp.servicemap.ServiceMapBuilder;
import org.opensearch.plugin.otlp.servicemap.ServiceMapMetrics;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.Map;

/** Executes one long-lived service-map trace-prefix partition. */
public final class ServiceMapTaskExecutor extends PersistentTasksExecutor<ServiceMapTaskParams> {

    private final Client client;
    private final ThreadPool threadPool;
    private final ServiceMapMetrics metrics;

    public ServiceMapTaskExecutor(Client client, ThreadPool threadPool) {
        this(client, threadPool, new ServiceMapMetrics());
    }

    public ServiceMapTaskExecutor(Client client, ThreadPool threadPool, ServiceMapMetrics metrics) {
        super(ServiceMapTaskParams.NAME, ThreadPool.Names.GENERIC);
        this.client = client;
        this.threadPool = threadPool;
        this.metrics = metrics;
    }

    @Override
    protected AllocatedPersistentTask createTask(
            long id,
            String type,
            String action,
            TaskId parentTaskId,
            PersistentTask<ServiceMapTaskParams> taskInProgress,
            Map<String, String> headers) {
        return new ServiceMapAllocatedTask(
            id, type, action, getDescription(taskInProgress), parentTaskId, headers);
    }

    @Override
    protected void nodeOperation(
            AllocatedPersistentTask task,
            ServiceMapTaskParams params,
            @Nullable PersistentTaskState state) {
        if (!params.isValid()) {
            task.markAsFailed(new IllegalArgumentException("Malformed service-map task parameters"));
            return;
        }
        ServiceMapGenerationConfig config = params.config();
        ServiceMapBuilder builder = new ServiceMapBuilder(
            client,
            threadPool,
            config.tracesIndex(),
            config.serviceMapIndex(),
            TimeValue.timeValueMillis(config.intervalMillis()),
            TimeValue.timeValueMillis(config.lookbackMillis()),
            config.groupByAttributes(),
            params.traceIdPrefix(),
            metrics);
        if (((ServiceMapAllocatedTask) task).start(builder)) {
            threadPool.executor(ThreadPool.Names.GENERIC).execute(builder::run);
        }
    }

    private static final class ServiceMapAllocatedTask extends AllocatedPersistentTask {

        private ServiceMapBuilder builder;

        private ServiceMapAllocatedTask(
                long id,
                String type,
                String action,
                String description,
                TaskId parentTask,
                Map<String, String> headers) {
            super(id, type, action, description, parentTask, headers);
        }

        private synchronized boolean start(ServiceMapBuilder newBuilder) {
            if (isCancelled()) {
                return false;
            }
            newBuilder.start();
            builder = newBuilder;
            return true;
        }

        @Override
        protected synchronized void onCancelled() {
            if (builder != null) {
                builder.stop();
                builder.close();
                builder = null;
            }
        }
    }
}
