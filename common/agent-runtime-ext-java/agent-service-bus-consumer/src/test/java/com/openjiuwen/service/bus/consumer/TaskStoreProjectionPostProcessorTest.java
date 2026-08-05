/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.a2a.ProjectingTaskStore;
import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests TaskStore decoration and durable Task-state projection behavior.
 *
 * @since 2026-07-22
 */
class TaskStoreProjectionPostProcessorTest {
    @Test
    void projectsTaskStatesWithPersistentRevision() {
        var admissions = new InMemoryBusTaskAdmissionStore();
        admissions.reserve(new Admission("tenant-a", "idem", "digest", "task-1", "CLIENT", "corr", "trace", "caller",
                "runtime", null, Admission.State.RESERVED));
        admissions.markAdmitted("tenant-a", "idem", "task-1");
        List<BusResponseProjection> seen = new ArrayList<>();
        var projectionStore = new InMemoryBusResponseProjectionStore();
        var coordinator = new BusTaskProjectionCoordinator(projectionStore, seen::add);
        coordinator.project(new BusResponseProjection("accepted", "INVOCATION_ACCEPTED", "tenant-a", "corr", "task-1",
                Instant.now(), Map.of(), null, null, null, null, null, "message", "ACCEPTED", 0));
        seen.clear();
        var processor = new TaskStoreProjectionPostProcessor(coordinator, admissions);
        Object processed = processor.postProcessAfterInitialization(new InMemoryTaskStore(), "taskStore");
        assertThat(processed).isInstanceOf(ProjectingTaskStore.class);
        if (!(processed instanceof TaskStore store)) {
            throw new AssertionError("TaskStore post processor returned an incompatible type");
        }

        store.save(task(TaskState.TASK_STATE_INPUT_REQUIRED, "2026-07-20T00:00:01Z"), false);
        assertThat(seen).isEmpty();
        processor.projectCurrent("tenant-a", "task-1");
        assertThat(seen).singleElement().satisfies(p -> {
            assertThat(p.eventType()).isEqualTo("INVOCATION_INPUT_REQUIRED");
            assertThat(p.revision()).isEqualTo(OffsetDateTime.parse("2026-07-20T00:00:01Z").toInstant().toEpochMilli());
            assertThat(p.data().get("task")).isInstanceOf(Task.class);
        });

        store.save(task(TaskState.TASK_STATE_COMPLETED, "2026-07-20T00:00:02Z"), false);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).eventType()).isEqualTo("INVOCATION_TERMINAL");
    }

    private static Task task(TaskState state, String timestamp) {
        return Task.builder().id("task-1").contextId("ctx")
                .status(new TaskStatus(state, null, OffsetDateTime.parse(timestamp))).build();
    }
}
