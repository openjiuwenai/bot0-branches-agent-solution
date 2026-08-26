/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.exceptions.JedisException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮审计快照存储（append-only）。轮次 seq 开轮即经 setnx 预占（决策 key 内嵌 seq，
 * 轮中决策落 key 依赖 seq 已确定），有界重试（最多 3 次，仍失败则本轮放弃并 WARN）；
 * setnx 后紧跟 expire 设短 TTL（两步间崩溃残留的占位键形成 seq 空洞，由回放侧缺洞
 * 标记容忍）。latest 索引用 set 覆盖写推进（索引维护，不受 append-only 约束）。
 *
 * <p>TTL 纪律：索引 key TTL 不短于记录 key；回放侧容忍"索引在、记录已过期"并显式
 * 标记缺洞，不静默跳过。
 *
 * @since 2026-08-26
 */
public class AuditSnapshotStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditSnapshotStore.class);

    /** seq 占位键短 TTL（秒）：开轮预占到闭轮写入的窗口。 */
    private static final long SEQ_HOLE_TTL_SECONDS = 300L;

    private static final int OCCUPY_MAX_ATTEMPTS = 3;
    private static final long OPEN_ROUND_TIMEOUT_MS = 600_000L;

    private final RedisTrajectoryStore store;
    private final AsyncTrajectoryWriter writer;
    private final Map<String, OccupiedRound> openRounds = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAllocated = new ConcurrentHashMap<>();

    /**
     * Creates the snapshot store.
     *
     * @param store  trajectory store
     * @param writer async writer
     */
    public AuditSnapshotStore(RedisTrajectoryStore store, AsyncTrajectoryWriter writer) {
        this.store = store;
        this.writer = writer;
    }

    /**
     * Occupies the next round seq for a conversation (bounded retry).
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @return allocated seq, or -1 when allocation failed (round is then dropped with WARN)
     */
    public long occupy(String tenantId, String conversationId) {
        String roundKey = key(tenantId, conversationId);
        // 内存单调下界：latest 索引经异步写推进（可能滞后/被背压丢弃），
        // 候选 seq 以 max(Redis latest, 本实例已分配下界) 为起点，冲突时递增而非重读
        long base = Math.max(readLatest(tenantId, conversationId), lastAllocated.getOrDefault(roundKey, 0L));
        for (int attempt = 0; attempt < OCCUPY_MAX_ATTEMPTS; attempt++) {
            long candidate = base + 1 + attempt;
            String seq = RedisTrajectoryStore.seq8(candidate);
            try {
                if (store.allocateSeq(RedisTrajectoryStore.auditKey(tenantId, conversationId, seq),
                        seq, SEQ_HOLE_TTL_SECONDS)) {
                    lastAllocated.put(roundKey, candidate);
                    openRounds.put(roundKey, new OccupiedRound(candidate));
                    return candidate;
                }
            } catch (JedisException | IllegalStateException e) {
                LOGGER.warn("audit seq occupy failed ({}), round dropped", e.getClass().getSimpleName());
                return -1L;
            }
        }
        LOGGER.warn("audit seq occupy exhausted {} attempts for conversation {}", OCCUPY_MAX_ATTEMPTS, conversationId);
        return -1L;
    }

    /**
     * Returns the latest committed round seq (for out-of-round decision records).
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @return latest seq (0 when none)
     */
    public long latestSeq(String tenantId, String conversationId) {
        return readLatest(tenantId, conversationId);
    }

    /**
     * Returns the seq of the currently open round (for decision records within the round).
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @return open round seq, or empty when no round is open
     */
    public Optional<Long> currentSeq(String tenantId, String conversationId) {
        evictStale();
        return Optional.ofNullable(openRounds.get(key(tenantId, conversationId))).map(OccupiedRound::seq);
    }

    /**
     * Closes a round: writes the snapshot body (record TTL) and advances the latest
     * index, both via the async writer.
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @param snapshotJson   snapshot record JSON
     */
    public void closeRound(String tenantId, String conversationId, String snapshotJson) {
        evictStale();
        OccupiedRound occupied = openRounds.remove(key(tenantId, conversationId));
        if (occupied == null) {
            return;
        }
        long seq = occupied.seq;
        String auditKey = RedisTrajectoryStore.auditKey(tenantId, conversationId, RedisTrajectoryStore.seq8(seq));
        String latestKey = RedisTrajectoryStore.auditLatestKey(tenantId, conversationId);
        String seq8 = RedisTrajectoryStore.seq8(seq);
        writer.submit(() -> {
            store.putRecord(auditKey, snapshotJson);
            store.advanceLatest(latestKey, seq8);
        });
    }

    /**
     * Replays snapshots of a conversation in seq order with explicit gap markers for
     * occupied-but-missing seqs (hole tolerance).
     *
     * @param tenantId       tenant id
     * @param conversationId conversation id
     * @return ordered entries; gap entries carry {@link ReplayEntry#isGap()} true
     */
    public List<ReplayEntry> replay(String tenantId, String conversationId) {
        long latest = readLatest(tenantId, conversationId);
        List<ReplayEntry> entries = new ArrayList<>();
        for (long seq = 1; seq <= latest; seq++) {
            final long currentSeq = seq;
            String seq8 = RedisTrajectoryStore.seq8(currentSeq);
            Optional<String> record = store.getRecord(
                    RedisTrajectoryStore.auditKey(tenantId, conversationId, seq8));
            entries.add(record.map(json -> new ReplayEntry(currentSeq, json, false))
                    .orElseGet(() -> new ReplayEntry(currentSeq, null, true)));
        }
        return entries;
    }

    private long readLatest(String tenantId, String conversationId) {
        return store.getRecord(RedisTrajectoryStore.auditLatestKey(tenantId, conversationId))
                .map(AuditSnapshotStore::parseLong).orElse(0L);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void evictStale() {
        long cutoff = System.currentTimeMillis() - OPEN_ROUND_TIMEOUT_MS;
        openRounds.values().removeIf(occupied -> occupied.createdAtMs < cutoff);
    }

    private static String key(String tenantId, String conversationId) {
        return tenantId + "|" + conversationId;
    }

    /** Occupy record with creation timestamp (stale eviction for never-closed rounds). */
    private static final class OccupiedRound {
        private final long seq;
        private final long createdAtMs = System.currentTimeMillis();

        private OccupiedRound(long seq) {
            this.seq = seq;
        }

        private long seq() {
            return seq;
        }
    }

    /**
     * One replay entry (snapshot body or an explicit gap marker).
     *
     * @param seq     round seq
     * @param json    snapshot JSON (null for gaps)
     * @param isGap   true when the seq was occupied but the body is missing
     */
    public record ReplayEntry(long seq, String json, boolean isGap) {
    }
}
