/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active task information for quota lifecycle management (DFX-002).
 *
 * <p>Maintains a map of conversationId to ActiveTaskInfo. The quota count
 * (tryAcquire/release) is managed by {@link TaskAdmissionControl}; this
 * class only records and removes task metadata for the query interface.
 *
 * @since 0.1.0
 */
public class TaskQuotaTracker implements ActiveTaskQuery {
    private final ConcurrentHashMap<String, ActiveTaskInfo> activeTasks = new ConcurrentHashMap<>();
    private final TaskAdmissionControl admissionControl;

    /**
     * Creates a tracker linked to the given admission control for snapshot max.
     *
     * @param admissionControl the admission control providing maxConcurrentTasks
     */
    public TaskQuotaTracker(TaskAdmissionControl admissionControl) {
        this.admissionControl = admissionControl;
    }

    /**
     * Record a task entering WORKING state.
     *
     * @param conversationId conversation identifier
     * @param taskId A2A task identifier
     */
    public void onTaskWorking(String conversationId, String taskId) {
        ActiveTaskInfo info = new ActiveTaskInfo(taskId, conversationId, "WORKING",
                Instant.now().toString());
        activeTasks.put(conversationId, info);
    }

    /**
     * Remove a task from the active map.
     *
     * @param conversationId conversation identifier
     */
    public void onTaskReleased(String conversationId) {
        activeTasks.remove(conversationId);
    }

    @Override
    public ConcurrencyLoadSnapshot snapshot() {
        List<ActiveTaskInfo> tasks = new ArrayList<>(activeTasks.values());
        return new ConcurrencyLoadSnapshot(
                admissionControl != null ? admissionControl.maxConcurrentTasks() : -1,
                tasks.size(),
                tasks);
    }
}
