/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 关键决策审计证据汇聚器。维护按 {@code conversationId|taskId} 键控的轮次缓冲：
 * rail 回调经 {@link AuditEventBridge} 写入工具事件与问答摘要，TaskStore 装饰层写入
 * 开轮/闭轮/委托/生命周期决策，终态闭轮时 flush 快照并清理缓冲；缓冲超时（10 分钟）
 * 兜底丢弃（惰性清理）。
 *
 * <p>rail 侧经 SessionContextHolder 只能拿到会话标识、看不到 A2A 层 taskId——由装饰层在
 * 开轮时维护 conversationId → 当前 taskId 映射供本收集器反查，闭轮时一并清理。
 *
 * <p>决策记录使用独立 key 空间（不与快照共享 seq 序列）：
 * {@code runtime:audit-dec:{tenantId}:{conversationId}:{seq:08d}:{decisionSeq:04d}}。
 *
 * @since 2026-08-26
 */
public class AuditEventCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditEventCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final long BUFFER_TIMEOUT_MS = 600_000L;
    private static final int SUMMARY_LIMIT = 200;

    private final AuditSnapshotStore snapshots;
    private final RedisTrajectoryStore store;
    private final AsyncTrajectoryWriter writer;
    private final Map<String, RoundBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<String, String> currentTask = new ConcurrentHashMap<>();

    /**
     * Creates the collector.
     *
     * @param snapshots snapshot store
     * @param store     trajectory store (decision keys)
     * @param writer    async writer
     */
    public AuditEventCollector(AuditSnapshotStore snapshots, RedisTrajectoryStore store,
                               AsyncTrajectoryWriter writer) {
        this.snapshots = snapshots;
        this.store = store;
        this.writer = writer;
    }

    /**
     * Opens a round: occupies the audit seq, opens the round buffer, and binds the
     * conversation to its current task.
     *
     * @param tenantId       tenant id (may be null, stored as "unknown")
     * @param conversationId conversation id
     * @param taskId         task id
     * @param traceId        trace id (may be null)
     * @param degraded       trace degraded flag
     * @param runId          run id
     * @return allocated seq, or -1 when seq occupation failed (round evidence dropped)
     */
    public long openRound(String tenantId, String conversationId, String taskId,
                          String traceId, boolean degraded, String runId) {
        if (conversationId == null || taskId == null) {
            return -1L;
        }
        String tenant = tenantId != null ? tenantId : "unknown";
        long seq = snapshots.occupy(tenant, conversationId);
        if (seq < 0) {
            return -1L;
        }
        RoundBuffer buffer = new RoundBuffer(seq, tenant, conversationId, taskId, runId, traceId, degraded);
        buffers.put(key(conversationId, taskId), buffer);
        currentTask.put(conversationId, taskId);
        return seq;
    }

    /**
     * Records a local tool call into the current round buffer (rail bridge).
     *
     * @param conversationId conversation id
     * @param toolName       tool name
     * @param status         finish / error
     * @param elapsedMs      elapsed milliseconds (0 when the rail has no timing source)
     */
    public void recordToolCall(String conversationId, String toolName, String status, long elapsedMs) {
        bufferOf(conversationId).ifPresent(buffer ->
                buffer.toolCalls.add(Map.of("name", toolName, "status", status, "elapsedMs", elapsedMs)));
    }

    /**
     * Records a tool-call decision evidence (args summary, elapsed, outcome). 不可逆性在
     * rail 侧不可判定——全部工具调用留证，不可逆子集由下游按工具名过滤。
     *
     * @param conversationId conversation id
     * @param toolName       tool name
     * @param status         finish / error
     * @param elapsedMs      elapsed milliseconds (0 when unavailable)
     * @param argsSummary    args summary (truncated here)
     */
    public void recordToolDecision(String conversationId, String toolName, String status,
                                   long elapsedMs, String argsSummary) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("toolName", toolName);
        fields.put("status", status);
        fields.put("elapsedMs", elapsedMs);
        fields.put("argsSummary", truncate(argsSummary));
        recordDecision(null, conversationId, "tool-call", fields);
    }

    /**
     * Records the user query / agent answer summaries into the round buffer (rail bridge).
     *
     * @param conversationId conversation id
     * @param userQuery      user query (truncated)
     * @param agentAnswer    agent answer (truncated)
     */
    public void recordExchange(String conversationId, String userQuery, String agentAnswer) {
        bufferOf(conversationId).ifPresent(buffer -> {
            buffer.userQuery = truncate(userQuery);
            buffer.agentAnswer = truncate(agentAnswer);
        });
    }

    /**
     * Records a cross-boundary delegation (shadow batch member).
     *
     * @param conversationId conversation id
     * @param taskId         parent task id
     * @param agentName      remote agent name
     * @param remoteRunId    remote run id
     * @param resultCategory result category (may be null)
     */
    public void recordDelegation(String conversationId, String taskId, String agentName,
                                 String remoteRunId, String resultCategory) {
        RoundBuffer buffer = buffers.get(key(conversationId, taskId));
        if (buffer == null) {
            return;
        }
        if (!buffer.delegationSeen.add(remoteRunId)) {
            // 影子 Task 在 resume 路径会被重复 save——同一 remoteTaskId 每轮只记一次
            return;
        }
        Map<String, Object> delegation = new LinkedHashMap<>();
        delegation.put("agentName", agentName);
        delegation.put("remoteRunId", remoteRunId);
        if (resultCategory != null && !resultCategory.isBlank()) {
            delegation.put("resultCategory", resultCategory);
        }
        buffer.delegations.add(delegation);
        recordDecision(buffer.tenantId, conversationId, "delegation", delegation);
    }

    /**
     * Records a decision evidence record (async write into the audit-dec key space).
     *
     * @param tenantId       tenant id (may be null, stored as "unknown")
     * @param conversationId conversation id
     * @param type           decision type (security / irreversible-tool / approval /
     *                       delegation / lifecycle)
     * @param fields         decision fields
     */
    public void recordDecision(String tenantId, String conversationId, String type,
                               Map<String, Object> fields) {
        Optional<RoundBuffer> buffer = bufferOf(conversationId);
        String tenant = tenantId != null ? tenantId : buffer.map(b -> b.tenantId).orElse("unknown");
        // 无 open round（轮外决策，如安全切面）时按 latest 已提交 seq 落盘，不丢弃
        long seq = buffer.map(b -> b.seq)
                .orElseGet(() -> snapshots.currentSeq(tenant, conversationId)
                        .orElseGet(() -> snapshots.latestSeq(tenant, conversationId)));
        if (seq < 0) {
            return;
        }
        int decisionSeq = buffer.map(b -> b.decisionSeq.incrementAndGet()).orElse(0);
        Map<String, Object> record = new LinkedHashMap<>(fields);
        record.put("type", type);
        record.put("seq", seq);
        record.put("decisionSeq", decisionSeq);
        record.put("recordedAt", now());
        String key = RedisTrajectoryStore.auditDecisionKey(tenant, conversationId,
                RedisTrajectoryStore.seq8(seq), RedisTrajectoryStore.seq4(decisionSeq));
        writer.submit(() -> store.putRecord(key, toJson(record)));
    }

    /**
     * Closes a round: flushes the snapshot and cleans up the buffer and task binding.
     *
     * @param conversationId conversation id
     * @param taskId         task id
     * @param finalState     terminal state name
     */
    public void closeRound(String conversationId, String taskId, String finalState) {
        RoundBuffer buffer = buffers.remove(key(conversationId, taskId));
        if (buffer == null) {
            return;
        }
        currentTask.remove(conversationId, taskId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("seq", buffer.seq);
        snapshot.put("runId", buffer.runId);
        snapshot.put("traceId", buffer.traceId);
        snapshot.put("traceDegraded", buffer.degraded);
        snapshot.put("startedAt", buffer.startedAt);
        snapshot.put("endedAt", now());
        snapshot.put("finalState", finalState);
        snapshot.put("toolCalls", buffer.toolCalls);
        snapshot.put("delegations", buffer.delegations);
        snapshot.put("userQuery", buffer.userQuery);
        snapshot.put("agentAnswer", buffer.agentAnswer);
        snapshot.put("tenantId", buffer.tenantId);
        snapshots.closeRound(buffer.tenantId, conversationId, toJson(snapshot));
    }

    private Optional<RoundBuffer> bufferOf(String conversationId) {
        evictStale();
        String taskId = currentTask.get(conversationId);
        if (taskId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(buffers.get(key(conversationId, taskId)));
    }

    private void evictStale() {
        long cutoff = System.currentTimeMillis() - BUFFER_TIMEOUT_MS;
        buffers.entrySet().removeIf(entry -> {
            boolean stale = entry.getValue().createdAtMs < cutoff;
            if (stale) {
                currentTask.values().removeIf(entry.getValue()::equals);
            }
            return stale;
        });
    }

    private static String key(String conversationId, String taskId) {
        return conversationId + "|" + taskId;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= SUMMARY_LIMIT ? value : value.substring(0, SUMMARY_LIMIT);
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Per-round evidence buffer. */
    private static final class RoundBuffer {
        private final long seq;
        private final String tenantId;
        private final String conversationId;
        private final String taskId;
        private final String runId;
        private final String traceId;
        private final boolean degraded;
        private final String startedAt = now();
        private final long createdAtMs = System.currentTimeMillis();
        private final List<Map<String, Object>> toolCalls = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> delegations = new CopyOnWriteArrayList<>();
        private final Set<String> delegationSeen = ConcurrentHashMap.newKeySet();
        private final AtomicInteger decisionSeq = new AtomicInteger();
        private volatile String userQuery = "";
        private volatile String agentAnswer = "";

        private RoundBuffer(long seq, String tenantId, String conversationId, String taskId,
                            String runId, String traceId, boolean degraded) {
            this.seq = seq;
            this.tenantId = tenantId;
            this.conversationId = conversationId;
            this.taskId = taskId;
            this.runId = runId;
            this.traceId = traceId;
            this.degraded = degraded;
        }
    }
}
