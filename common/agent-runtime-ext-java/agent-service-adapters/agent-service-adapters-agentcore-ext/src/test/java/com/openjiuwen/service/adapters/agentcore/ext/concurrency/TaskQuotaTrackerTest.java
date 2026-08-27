/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaskQuotaTracker}.
 *
 * @since 0.1.0
 */
class TaskQuotaTrackerTest {
    @Test
    void onAdmitted_recordsActiveTask() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted("task-1", "conv-1");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getTasks()).hasSize(1);
        assertThat(snapshot.getTasks().get(0).getTaskId()).isEqualTo("task-1");
        assertThat(snapshot.getTasks().get(0).getConversationId()).isEqualTo("conv-1");
        // Entry contract (L2 §2.3): WORKING status with a non-empty start time
        assertThat(snapshot.getTasks().get(0).getStatus()).isEqualTo("WORKING");
        assertThat(snapshot.getTasks().get(0).getStartedAt()).isNotBlank();
    }

    @Test
    void onReleased_removesActiveTask() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted("task-1", "conv-1");
        tracker.onReleased("task-1", "conv-1");
        assertThat(tracker.snapshot().getTasks()).isEmpty();
    }

    @Test
    void snapshot_returnsCorrectCount() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        // Listener notifications bracket an acquired permit in production
        // (executor admission scope), so tests must acquire the permit too.
        assertThat(gate.tryAcquire()).isTrue();
        tracker.onAdmitted("task-1", "conv-1");
        assertThat(gate.tryAcquire()).isTrue();
        tracker.onAdmitted("task-2", "conv-2");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getCurrentActiveTasks()).isEqualTo(2);
        // Snapshot aggregates the configured limit from the admission control
        assertThat(snapshot.getMaxConcurrentTasks()).isEqualTo(5);
    }

    @Test
    void snapshot_countFollowsGate_notListenerMap() {
        // Issue #155: currentActiveTasks must mirror the authoritative quota
        // occupancy (gate.currentCount(), the value 503 decisions use), not
        // the listener-fed map size. Simulates the tryAcquire→onAdmitted and
        // release→onReleased windows by driving only one side of each pair.
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);

        // Window 1: permit acquired, onAdmitted not yet fired.
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(tracker.snapshot().getCurrentActiveTasks()).isEqualTo(1);
        assertThat(tracker.snapshot().getTasks()).isEmpty();

        tracker.onAdmitted("task-w1", "conv-1");

        // Window 2: permit released, onReleased not yet fired.
        gate.release();
        assertThat(tracker.snapshot().getCurrentActiveTasks()).isZero();
        assertThat(tracker.snapshot().getTasks()).hasSize(1);

        tracker.onReleased("task-w1", "conv-1");
        assertThat(tracker.snapshot().getCurrentActiveTasks()).isZero();
        assertThat(tracker.snapshot().getTasks()).isEmpty();
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
    void sameConversation_concurrentTasks_trackedIndependently() {
        // taskId keying: two in-flight tasks from one conversation must both
        // be visible (previously the conversationId key overwrote task-A).
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted("task-A", "conv-1");
        tracker.onAdmitted("task-B", "conv-1");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getTasks()).hasSize(2);
        assertThat(snapshot.getTasks()).extracting(t -> t.getTaskId())
                .containsExactlyInAnyOrder("task-A", "task-B");
    }

    @Test
    void onReleased_onlyRemovesMatchingTask() {
        // Releasing task-A must not evict task-B even within one conversation.
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted("task-A", "conv-1");
        tracker.onAdmitted("task-B", "conv-1");
        tracker.onReleased("task-A", "conv-1");
        ConcurrencyLoadSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.getTasks()).hasSize(1);
        assertThat(snapshot.getTasks().get(0).getTaskId()).isEqualTo("task-B");
    }

    @Test
    void nullTaskId_fallsBackToConversationKey() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted(null, "conv-no-task");
        assertThat(tracker.snapshot().getTasks()).hasSize(1);
        tracker.onReleased(null, "conv-no-task");
        assertThat(tracker.snapshot().getTasks()).isEmpty();
    }

    @Test
    void onReleased_idempotent_forUnknownTask() {
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onReleased("never-seen", "conv-x");
        assertThat(tracker.snapshot().getTasks()).isEmpty();
    }

    @Test
    void taskId_reusedAcrossSequentialAdmissions_notStale() {
        // An INPUT_REQUIRED resume re-admits the same taskId; the stale entry
        // must be replaced, not duplicated.
        TaskAdmissionControl gate = new TaskAdmissionControl(5);
        TaskQuotaTracker tracker = new TaskQuotaTracker(gate);
        tracker.onAdmitted("task-r", "conv-1");
        tracker.onReleased("task-r", "conv-1");
        tracker.onAdmitted("task-r", "conv-1");
        assertThat(tracker.snapshot().getTasks()).hasSize(1);
    }
}
