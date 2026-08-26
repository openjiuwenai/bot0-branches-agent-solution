/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AuditEventCollector 的单元测试：开轮-工具-委托-决策-闭轮全链路快照、决策 key 计数、
 * 缓冲清理与桥接静态安全性。
 */
class AuditEventCollectorTest {
    private final RedisTrajectoryStore store = mock(RedisTrajectoryStore.class);
    private final AsyncTrajectoryWriter writer = mock(AsyncTrajectoryWriter.class);
    private AuditEventCollector collector;

    @BeforeEach
    void setUp() {
        AuditSnapshotStore snapshots = new AuditSnapshotStore(store, writer);
        collector = new AuditEventCollector(snapshots, store, writer);
        when(store.allocateSeq(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        when(store.getRecord(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        AuditEventBridge.clear();
    }

    @Test
    void fullRoundLifecycleProducesSnapshotWithEvidence() {
        collector.openRound("tenant-1", "conv-1", "task-1", "trace-1", false, "task-1#1");
        collector.recordToolCall("conv-1", "todo_create", "finish", 12L);
        collector.recordDelegation("conv-1", "task-1", "versatile-agent", "remote-9", "COMPLETED");
        collector.recordExchange("conv-1", "推荐基金", "建议配置A");
        collector.closeRound("conv-1", "task-1", "TASK_STATE_COMPLETED");
        flush();
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).putRecord(
                eq(RedisTrajectoryStore.auditKey("tenant-1", "conv-1", "00000001")), jsonCaptor.capture());
        String snapshot = jsonCaptor.getValue();
        assertThat(snapshot).contains("\"runId\":\"task-1#1\"").contains("\"traceId\":\"trace-1\"")
                .contains("todo_create").contains("versatile-agent").contains("remote-9")
                .contains("推荐基金").contains("建议配置A")
                .contains("\"finalState\":\"TASK_STATE_COMPLETED\"");
        // 委托同时落决策记录（独立 key 空间，decisionSeq 递增）
        verify(store).putRecord(
                eq(RedisTrajectoryStore.auditDecisionKey("tenant-1", "conv-1", "00000001", "0001")),
                contains("\"type\":\"delegation\""));
    }

    @Test
    void toolEventWithoutOpenRoundIsDropped() {
        collector.recordToolCall("conv-x", "t", "finish", 1L);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void decisionRecordsIncrementDecisionSeq() {
        collector.openRound("t", "conv-1", "task-1", null, true, "task-1#1");
        collector.recordDecision("t", "conv-1", "lifecycle", Map.of("to", "A"));
        collector.recordDecision("t", "conv-1", "lifecycle", Map.of("to", "B"));
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.auditDecisionKey("t", "conv-1", "00000001", "0001")),
                contains("\"to\":\"A\""));
        verify(store).putRecord(eq(RedisTrajectoryStore.auditDecisionKey("t", "conv-1", "00000001", "0002")),
                contains("\"to\":\"B\""));
    }

    @Test
    void closeWithoutOpenIsSilent() {
        collector.closeRound("conv-x", "task-x", "TASK_STATE_COMPLETED");
        org.mockito.Mockito.verifyNoInteractions(writer);
    }

    @Test
    void bridgeForwardsWhenRegisteredAndIsSafeWhenNot() {
        AuditEventBridge.recordToolCall("conv-1", "t", "finish", 1L);
        collector.openRound("t", "conv-1", "task-1", null, false, "task-1#1");
        AuditEventBridge.register(collector);
        AuditEventBridge.recordToolCall("conv-1", "todo_create", "finish", 5L);
        collector.closeRound("conv-1", "task-1", "TASK_STATE_COMPLETED");
        flush();
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).putRecord(
                eq(RedisTrajectoryStore.auditKey("t", "conv-1", "00000001")), jsonCaptor.capture());
        assertThat(jsonCaptor.getValue()).contains("todo_create");
        AuditEventBridge.clear();
    }

    private void flush() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(writer, atLeastOnce()).submit(captor.capture());
        List<Runnable> tasks = new ArrayList<>(captor.getAllValues());
        tasks.forEach(Runnable::run);
        org.mockito.Mockito.clearInvocations(writer);
    }
}
