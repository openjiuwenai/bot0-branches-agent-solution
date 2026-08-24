/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active task information for quota lifecycle management (DFX-002).
 *
 * <p>Implements {@link TaskAdmissionListener} so the A2A executor drives the
 * recording at the exact points where an admission quota slot is acquired and
 * released. A task is therefore visible from {@code snapshot()} for precisely
 * as long as it occupies admission quota ({@code TaskAdmissionGate#currentCount()}
 * and the probe's {@code currentActiveTasks} can never diverge), which spans
 * the whole orchestrated processing — including agent construction and any
 * orchestrator re-drive — not just the AgentHandler invocation.
 *
 * <p>Entries are keyed by taskId (falling back to the conversationId when a
 * transport provides no task id), so concurrent tasks from the same
 * conversation are tracked independently instead of overwriting each other.
 * The quota count itself (tryAcquire/release) is managed by the
 * {@link TaskAdmissionGate}; this class only records task metadata.
 *
 * @since 0.1.0
 */
public class TaskQuotaTracker implements ActiveTaskQuery, TaskAdmissionListener {
    private final ConcurrentHashMap<String, ActiveTaskInfo> activeTasks = new ConcurrentHashMap<>();

    private final TaskAdmissionGate admissionGate;

    /**
     * Creates a tracker linked to the given admission gate for snapshot max.
     *
     * <p>Accepts any {@link TaskAdmissionGate} implementation (default or
     * user-defined) so the tracker keeps working when the default
     * {@code TaskAdmissionControl} is backed off by a custom gate bean.
     *
     * @param admissionGate the admission gate providing the concurrency limit
     */
    public TaskQuotaTracker(TaskAdmissionGate admissionGate) {
        this.admissionGate = admissionGate;
    }

    @Override
    public void onAdmitted(String taskId, String conversationId) {
        ActiveTaskInfo info = new ActiveTaskInfo(taskId, conversationId, "WORKING",
                Instant.now().toString());
        activeTasks.put(key(taskId, conversationId), info);
    }

    @Override
    public void onReleased(String taskId, String conversationId) {
        activeTasks.remove(key(taskId, conversationId));
    }

    @Override
    public ConcurrencyLoadSnapshot snapshot() {
        List<ActiveTaskInfo> tasks = new ArrayList<>(activeTasks.values());
        return new ConcurrencyLoadSnapshot(
                admissionGate != null ? admissionGate.limit() : -1,
                tasks.size(),
                tasks);
    }

    private static String key(String taskId, String conversationId) {
        return taskId != null ? taskId : "conversation:" + conversationId;
    }
}
