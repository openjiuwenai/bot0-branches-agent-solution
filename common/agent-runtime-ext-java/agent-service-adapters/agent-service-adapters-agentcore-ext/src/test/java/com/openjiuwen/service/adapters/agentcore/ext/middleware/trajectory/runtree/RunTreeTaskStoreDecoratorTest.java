/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RunTreeTaskStoreDecorator 的单元测试：开轮/续轮/收尾节点、影子批边、重启恢复、委托透传。
 */
class RunTreeTaskStoreDecoratorTest {
    private final TaskStore delegate = mock(TaskStore.class);
    private final RedisTrajectoryStore store = mock(RedisTrajectoryStore.class);
    private final AsyncTrajectoryWriter writer = mock(AsyncTrajectoryWriter.class);
    private final TraceContextCarrier carrier = TraceContextCarrier.create(86400L);
    private RunTreeTaskStoreDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new RunTreeTaskStoreDecorator(delegate, carrier, store, writer);
        carrier.put("conv-1", new TraceContextCarrier.Entry(
                "1cd0e9b4ebc1bc708de6aae571b089ce", false, "a2a", "tenant-1", Instant.now()));
    }

    @Test
    void firstSaveOpensRoundOneNodeAndBackfillsCarrier() {
        decorator.save(task("task-1", TaskState.TASK_STATE_SUBMITTED, null), true);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runKey("task-1#1")), contains("\"startedAt\""));
        verify(store).putIndex(RedisTrajectoryStore.traceIndexKey(
                "1cd0e9b4ebc1bc708de6aae571b089ce", "task-1#1"));
        verify(store).putIndex(RedisTrajectoryStore.convIndexKey("conv-1", "task-1#1"));
        assertThat(carrier.find("conv-1").get().getCurrentRunId()).contains("task-1#1");
    }

    @Test
    void resumeFromInputRequiredOpensNextRound() {
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        decorator.save(task("task-1", TaskState.TASK_STATE_INPUT_REQUIRED, null), false);
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runKey("task-1#2")), contains("\"startedAt\""));
    }

    @Test
    void terminalSaveClosesRoundWithFinalState() {
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        decorator.save(task("task-1", TaskState.TASK_STATE_COMPLETED, null), false);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runKey("task-1#1")),
                contains("\"finalState\":\"TASK_STATE_COMPLETED\""));
    }

    @Test
    void shadowTaskProducesEdgesWithFullParentRunId() {
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("parentTaskId", "task-1");
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("remoteTaskId", "remote-9");
        member.put("toolCallId", "call_1");
        member.put("agentName", "versatile-agent");
        member.put("state", "COMPLETED");
        member.put("resultCategory", "COMPLETED");
        snapshot.put("members", List.of(member));
        decorator.save(task("shadow:task-1:batch", TaskState.TASK_STATE_WORKING,
                Map.of("_remote_batch", snapshot)), false);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runEdgeKey("task-1#1", "remote-9")),
                contains("\"agentName\":\"versatile-agent\""));
        verify(store).putIndex(RedisTrajectoryStore.parentIndexKey("task-1#1", "remote-9"));
    }

    @Test
    void restartRecoverySeedsRoundSeqFromNodeKeys() {
        when(store.exists(RedisTrajectoryStore.runKey("task-1#1"))).thenReturn(true);
        when(store.scanRoundKeys("task-1")).thenReturn(
                List.of(RedisTrajectoryStore.runKey("task-1#1"), RedisTrajectoryStore.runKey("task-1#2")));
        when(store.getRecord(RedisTrajectoryStore.runKey("task-1#2")))
                .thenReturn(java.util.Optional.of("{\"traceId\":\"t-1\"}"));
        decorator.save(task("task-1", TaskState.TASK_STATE_INPUT_REQUIRED, null), false);
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runKey("task-1#3")), contains("\"startedAt\""));
    }

    @Test
    void recoveredInterruptedStateTriggersNextRoundOnResume() {
        // G2 直接钉：恢复节点的 finalState=INPUT_REQUIRED 映回 lastState，重启后续跑直接开新轮
        when(store.exists(RedisTrajectoryStore.runKey("task-1#1"))).thenReturn(true);
        when(store.scanRoundKeys("task-1")).thenReturn(
                List.of(RedisTrajectoryStore.runKey("task-1#1"), RedisTrajectoryStore.runKey("task-1#2")));
        when(store.getRecord(RedisTrajectoryStore.runKey("task-1#2"))).thenReturn(java.util.Optional.of(
                "{\"traceId\":\"t-1\",\"finalState\":\"TASK_STATE_INPUT_REQUIRED\"}"));
        decorator.save(task("task-1", TaskState.TASK_STATE_WORKING, null), false);
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.runKey("task-1#3")), contains("\"startedAt\""));
    }

    @Test
    void plainCallsPassThrough() {
        decorator.get("task-1");
        decorator.delete("task-1");
        verify(delegate).get("task-1");
        verify(delegate).delete("task-1");
    }

    private void flush() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(writer, atLeastOnce()).submit(captor.capture());
        List<Runnable> tasks = new ArrayList<>(captor.getAllValues());
        tasks.forEach(Runnable::run);
        org.mockito.Mockito.clearInvocations(writer);
    }

    private static Task task(String id, TaskState state, Map<String, Object> metadata) {
        Task.Builder builder = Task.builder().id(id).contextId("conv-1").status(new TaskStatus(state));
        if (metadata != null) {
            builder.metadata(metadata);
        }
        return builder.build();
    }
}
