/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskQuotaTracker}.
 *
 * @since 0.1.0
 */
class TaskQuotaTrackerTest {

    @Test
    void onTaskWorking_recordsActiveTask() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onTaskWorking("conv-1", "task-1");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getTasks()).hasSize(1);
        assertThat(snapshot.getTasks().get(0).getConversationId()).isEqualTo("conv-1");
        assertThat(snapshot.getTasks().get(0).getTaskId()).isEqualTo("task-1");
        // Entry contract (L2 §2.3): WORKING status with a non-empty start time
        assertThat(snapshot.getTasks().get(0).getStatus()).isEqualTo("WORKING");
        assertThat(snapshot.getTasks().get(0).getStartedAt()).isNotBlank();
    }

    @Test
    void onTaskReleased_removesActiveTask() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onTaskWorking("conv-1", "task-1");
        tracker.onTaskReleased("conv-1");
        assertThat(tracker.snapshot().getTasks()).isEmpty();
    }

    @Test
    void snapshot_returnsCorrectCount() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onTaskWorking("conv-1", "task-1");
        tracker.onTaskWorking("conv-2", "task-2");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getCurrentActiveTasks()).isEqualTo(2);
        // Snapshot aggregates the configured limit from the admission control
        assertThat(snapshot.getMaxConcurrentTasks()).isEqualTo(5);
    }

    @Test
    void snapshot_empty_whenNoActiveTasks() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getCurrentActiveTasks()).isZero();
        assertThat(snapshot.getTasks()).isEmpty();
    }

    @Test
    void onTaskWorking_overwritesSameConversation() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onTaskWorking("conv-1", "task-A");
        tracker.onTaskWorking("conv-1", "task-B");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getTasks()).hasSize(1);
        assertThat(snapshot.getTasks().get(0).getTaskId()).isEqualTo("task-B");
    }

    @Test
    void onTaskReleased_idempotent_forUnknownConversation() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onTaskReleased("never-seen");
        assertThat(tracker.snapshot().getTasks()).isEmpty();
    }
}
