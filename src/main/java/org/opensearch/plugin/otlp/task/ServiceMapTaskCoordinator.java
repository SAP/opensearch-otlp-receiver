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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.lifecycle.AbstractLifecycleComponent;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.action.ActionListener;
import org.opensearch.persistent.PersistentTasksCustomMetadata;
import org.opensearch.persistent.PersistentTasksCustomMetadata.PersistentTask;
import org.opensearch.persistent.PersistentTasksService;
import org.opensearch.threadpool.Scheduler;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains one persistent service-map task for each hexadecimal trace-id prefix. */
public final class ServiceMapTaskCoordinator extends AbstractLifecycleComponent implements ClusterStateListener {

    private static final Logger LOG = LogManager.getLogger(ServiceMapTaskCoordinator.class);
    private static final TimeValue RETRY_DELAY = TimeValue.timeValueSeconds(10);
    private static final int PREFIX_PARTITIONS = 16;
    private static final String TASK_ID_PREFIX = "otlp-service-map-prefix-";

    private final ClusterService clusterService;
    private final ThreadPool threadPool;
    private final PersistentTasksService tasksService;
    private final boolean enabled;
    private final List<ServiceMapTaskParams> desiredTasks;
    private final AtomicBoolean mutationInFlight = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();

    private volatile boolean running;
    private volatile Scheduler.Cancellable retryTask;

    public ServiceMapTaskCoordinator(
            Client client,
            ClusterService clusterService,
            ThreadPool threadPool,
            boolean enabled,
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes) {
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        this.tasksService = new PersistentTasksService(clusterService, threadPool, client);
        this.enabled = enabled;
        this.desiredTasks = desiredTasks(
            tracesIndex, serviceMapIndex, interval, lookbackWindow, groupByAttributes);
    }

    @Override
    protected void doStart() {
        running = true;
        clusterService.addListener(this);
    }

    @Override
    protected void doStop() {
        running = false;
        clusterService.removeListener(this);
        Scheduler.Cancellable scheduled = retryTask;
        if (scheduled != null) {
            scheduled.cancel();
        }
    }

    @Override
    protected void doClose() {}

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        reconcile(event.state());
    }

    private void reconcile(ClusterState state) {
        if (!running || !state.nodes().isLocalNodeElectedClusterManager() || mutationInFlight.get()) {
            return;
        }

        PersistentTasksCustomMetadata metadata =
            state.metadata().custom(PersistentTasksCustomMetadata.TYPE);
        if (!enabled) {
            removeFirstServiceMapTask(metadata);
            return;
        }

        for (ServiceMapTaskParams desired : desiredTasks) {
            String taskId = taskId(desired.traceIdPrefix());
            PersistentTask<?> existing = metadata == null ? null : metadata.getTask(taskId);
            if (existing == null) {
                startTask(taskId, desired);
                return;
            }
            if (!ServiceMapTaskParams.NAME.equals(existing.getTaskName())) {
                LOG.error("Persistent task id [{}] is owned by unexpected task type [{}]",
                    taskId, existing.getTaskName());
                continue;
            }
            if (!desired.equals(existing.getParams())) {
                LOG.info("Replacing service-map task [{}] because its configuration changed", taskId);
                removeTask(taskId);
                return;
            }
        }
    }

    private void removeFirstServiceMapTask(PersistentTasksCustomMetadata metadata) {
        if (metadata == null) {
            return;
        }
        for (PersistentTask<?> task : metadata.tasks()) {
            if (ServiceMapTaskParams.NAME.equals(task.getTaskName())) {
                removeTask(task.getId());
                return;
            }
        }
    }

    private void startTask(String taskId, ServiceMapTaskParams params) {
        if (!mutationInFlight.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Creating service-map persistent task [{}] for trace prefix [{}]",
            taskId, params.traceIdPrefix());
        tasksService.sendStartRequest(taskId, ServiceMapTaskParams.NAME, params,
            new ActionListener<>() {
                @Override
                public void onResponse(PersistentTask<ServiceMapTaskParams> task) {
                    mutationComplete();
                }

                @Override
                public void onFailure(Exception e) {
                    mutationFailed("create", taskId, e);
                }
            });
    }

    private void removeTask(String taskId) {
        if (!mutationInFlight.compareAndSet(false, true)) {
            return;
        }
        tasksService.sendRemoveRequest(taskId, new ActionListener<>() {
            @Override
            public void onResponse(PersistentTask<?> task) {
                mutationComplete();
            }

            @Override
            public void onFailure(Exception e) {
                mutationFailed("remove", taskId, e);
            }
        });
    }

    private void mutationComplete() {
        mutationInFlight.set(false);
        executeReconcile();
    }

    private void mutationFailed(String operation, String taskId, Exception failure) {
        mutationInFlight.set(false);
        LOG.warn("Failed to {} service-map persistent task [{}]; retrying",
            operation, taskId, failure);
        scheduleRetry();
    }

    private void executeReconcile() {
        if (running) {
            threadPool.executor(ThreadPool.Names.GENERIC)
                .execute(() -> reconcile(clusterService.state()));
        }
    }

    private void scheduleRetry() {
        if (!running || !retryScheduled.compareAndSet(false, true)) {
            return;
        }
        retryTask = threadPool.schedule(() -> {
            retryScheduled.set(false);
            reconcile(clusterService.state());
        }, RETRY_DELAY, ThreadPool.Names.GENERIC);
    }

    static String taskId(String prefix) {
        return TASK_ID_PREFIX + prefix;
    }

    private static List<ServiceMapTaskParams> desiredTasks(
            String tracesIndex,
            String serviceMapIndex,
            TimeValue interval,
            TimeValue lookbackWindow,
            List<String> groupByAttributes) {
        List<ServiceMapTaskParams> tasks = new ArrayList<>(PREFIX_PARTITIONS);
        for (int partition = 0; partition < PREFIX_PARTITIONS; partition++) {
            tasks.add(new ServiceMapTaskParams(
                Integer.toHexString(partition),
                tracesIndex,
                serviceMapIndex,
                interval.millis(),
                lookbackWindow.millis(),
                groupByAttributes));
        }
        return List.copyOf(tasks);
    }
}
