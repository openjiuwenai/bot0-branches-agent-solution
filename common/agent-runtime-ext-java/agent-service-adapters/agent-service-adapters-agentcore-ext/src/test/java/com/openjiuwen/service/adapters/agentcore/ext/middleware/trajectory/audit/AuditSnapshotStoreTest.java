/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import redis.clients.jedis.exceptions.JedisException;

/**
 * AuditSnapshotStore 的单元测试：seq 预占有界重试、闭轮写快照+推进 latest、回放排序与缺洞标记、
 * Redis 故障降级。
 */
class AuditSnapshotStoreTest {
    private final RedisTrajectoryStore store = mock(RedisTrajectoryStore.class);
    private final AsyncTrajectoryWriter writer = mock(AsyncTrajectoryWriter.class);
    private final AuditSnapshotStore snapshots = new AuditSnapshotStore(store, writer);

    @Test
    void occupyAllocatesSequentialSeqs() {
        when(store.allocateSeq(anyString(), anyString(), anyLong())).thenReturn(true);
        when(store.getRecord(RedisTrajectoryStore.auditLatestKey("t", "c"))).thenReturn(Optional.empty());
        assertThat(snapshots.occupy("t", "c")).isEqualTo(1L);

        when(store.getRecord(RedisTrajectoryStore.auditLatestKey("t", "c"))).thenReturn(Optional.of("00000001"));
        assertThat(snapshots.occupy("t", "c")).isEqualTo(2L);
        assertThat(snapshots.currentSeq("t", "c")).contains(2L);
    }

    @Test
    void occupyDegradesToMinusOneOnRedisFailure() {
        when(store.getRecord(anyString())).thenReturn(Optional.empty());
        when(store.allocateSeq(anyString(), anyString(), anyLong()))
                .thenThrow(new JedisException("down"));
        assertThat(snapshots.occupy("t", "c")).isEqualTo(-1L);
    }

    @Test
    void closeRoundWritesSnapshotAndAdvancesLatest() {
        when(store.allocateSeq(anyString(), anyString(), anyLong())).thenReturn(true);
        when(store.getRecord(anyString())).thenReturn(Optional.empty());
        snapshots.occupy("t", "c");
        snapshots.closeRound("t", "c", "{\"seq\":1}");
        flush();
        verify(store).putRecord(eq(RedisTrajectoryStore.auditKey("t", "c", "00000001")), eq("{\"seq\":1}"));
        verify(store).advanceLatest(RedisTrajectoryStore.auditLatestKey("t", "c"), "00000001");
    }

    @Test
    void replayIsOrderedWithExplicitGapMarkers() {
        when(store.getRecord(RedisTrajectoryStore.auditLatestKey("t", "c"))).thenReturn(Optional.of("00000003"));
        when(store.getRecord(RedisTrajectoryStore.auditKey("t", "c", "00000001"))).thenReturn(Optional.of("s1"));
        when(store.getRecord(RedisTrajectoryStore.auditKey("t", "c", "00000002"))).thenReturn(Optional.empty());
        when(store.getRecord(RedisTrajectoryStore.auditKey("t", "c", "00000003"))).thenReturn(Optional.of("s3"));
        List<AuditSnapshotStore.ReplayEntry> entries = snapshots.replay("t", "c");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).json()).isEqualTo("s1");
        assertThat(entries.get(1).isGap()).isTrue();
        assertThat(entries.get(2).json()).isEqualTo("s3");
    }

    private void flush() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(writer, atLeastOnce()).submit(captor.capture());
        List<Runnable> tasks = new ArrayList<>(captor.getAllValues());
        tasks.forEach(Runnable::run);
    }
}
