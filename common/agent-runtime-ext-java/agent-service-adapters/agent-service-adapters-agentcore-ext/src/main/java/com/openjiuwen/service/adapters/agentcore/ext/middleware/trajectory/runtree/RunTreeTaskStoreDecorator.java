/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit.AuditEventCollector;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行树采集装饰器：包装任意 {@link TaskStore} Bean（{@code WriteThrottlingTaskStore} 外侧，
 * 能看到含节流丢弃前的全部 save），在 save 时识别三类写入并异步落执行树记录：
 *
 * <ul>
 *   <li>新 Task 首次 save（seen-set 判定）→ 开第 1 轮节点（run_id={taskId}#1, kind=local）；
 *   重启后以 {@code runtime:run:{taskId}#1} 节点键存在性补位并经节点键计数恢复 roundSeq。</li>
 *   <li>已有 Task 从 input-required 回到 working → roundSeq+1 开新轮节点。</li>
 *   <li>影子 Task（id 前缀 {@code shadow:}，metadata 含 {@code _remote_batch} 快照）→ 按快照
 *   每个 member 产委托边（parent_run_id 全格式 → member.remoteTaskId）。</li>
 *   <li>终态迁移（completed/failed/canceled/input-required/auth-required）→ 当前轮节点收尾
 *   （endedAt、finalState）；非中断终态后清理内存轮次（中断态保留以待 resume）。</li>
 * </ul>
 *
 * <p>同 task 并发 save（取消/流式写穿/恢复可来自不同线程）按 RoundState 实例串行化状态迁移；
 * 开轮时回填 carrier 的 currentRunId（供出站装饰器读取）；节点携带 traceId/tenantId/
 * parentRunId（经 carrier 条目）；全部记录写经 {@link AsyncTrajectoryWriter} 异步刷写，
 * 任何环节失败不阻塞、不影响 save 主流程。
 *
 * @since 2026-08-26
 */
public class RunTreeTaskStoreDecorator implements TaskStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunTreeTaskStoreDecorator.class);

    private static final String SHADOW_PREFIX = "shadow:";
    private static final String REMOTE_BATCH_METADATA = "_remote_batch";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final TaskStore delegate;
    private final TraceContextCarrier carrier;
    private final RedisTrajectoryStore store;
    private final AsyncTrajectoryWriter writer;
    private final AuditEventCollector audit;
    private final Map<String, RoundState> rounds = new ConcurrentHashMap<>();

    /**
     * Creates the decorator.
     *
     * @param delegate upstream task store
     * @param carrier  trace context carrier
     * @param store    trajectory record store
     * @param writer   async writer
     * @param audit    audit event collector (may be null; audit events then skipped)
     */
    public RunTreeTaskStoreDecorator(TaskStore delegate, TraceContextCarrier carrier,
                                     RedisTrajectoryStore store, AsyncTrajectoryWriter writer,
                                     AuditEventCollector audit) {
        this.delegate = delegate;
        this.carrier = carrier;
        this.store = store;
        this.writer = writer;
        this.audit = audit;
    }

    @Override
    public void save(Task task, boolean first) {
        delegate.save(task, first);
        try {
            observe(task);
        } catch (IllegalStateException | ClassCastException | NullPointerException e) {
            LOGGER.warn("run-tree observe failed for task {}: {}", task != null ? task.id() : null,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public Task get(String taskId) {
        return delegate.get(taskId);
    }

    @Override
    public void delete(String taskId) {
        delegate.delete(taskId);
    }

    @Override
    public ListTasksResult list(ListTasksParams params) {
        return delegate.list(params);
    }

    private void observe(Task task) {
        if (task == null || task.id() == null) {
            return;
        }
        if (task.id().startsWith(SHADOW_PREFIX)) {
            observeShadow(task);
            return;
        }
        RoundState round = rounds.computeIfAbsent(task.id(), this::recoverRound);
        synchronized (round) {
            TaskState current = task.status() != null ? task.status().state() : null;
            if (round.roundSeq == 0) {
                openRound(task, round, 1);
            } else if (current == TaskState.TASK_STATE_WORKING && round.lastState != null
                    && round.lastState.isInterrupted()) {
                openRound(task, round, round.roundSeq + 1);
            }
            if (current != null && isTerminal(current)) {
                closeRound(round, current);
                if (!current.isInterrupted()) {
                    rounds.remove(task.id(), round);
                }
            }
            round.lastState = current;
        }
    }

    private void observeShadow(Task task) {
        if (audit != null) {
            recordShadowDelegations(task);
        }
        Object snapshot = task.metadata() != null ? task.metadata().get(REMOTE_BATCH_METADATA) : null;
        if (!(snapshot instanceof Map<?, ?> batch)) {
            return;
        }
        Object parentTaskId = batch.get("parentTaskId");
        Object members = batch.get("members");
        if (!(parentTaskId instanceof String parent) || !(members instanceof List<?> memberList)) {
            return;
        }
        String parentRunId = fullRunId(parent);
        for (Object member : memberList) {
            if (!(member instanceof Map<?, ?> memberMap)) {
                continue;
            }
            writeEdge(parentRunId, memberMap);
        }
    }

    private void recordShadowDelegations(Task task) {
        Object snapshot = task.metadata() != null ? task.metadata().get(REMOTE_BATCH_METADATA) : null;
        if (!(snapshot instanceof Map<? , ?> batch) || !(batch.get("members") instanceof List<?> memberList)
                || !(batch.get("parentTaskId") instanceof String parentId)) {
            return;
        }
        for (Object member : memberList) {
            if (member instanceof Map<?, ?> memberMap
                    && memberMap.get("remoteTaskId") instanceof String remote && !remote.isBlank()) {
                audit.recordDelegation(task.contextId(), parentId,
                        text(memberMap.get("agentName")).orElse("unknown"), remote,
                        text(memberMap.get("resultCategory")).orElse(null),
                        text(memberMap.get("toolCallId")).orElse(null), fullRunId(parentId));
            }
        }
    }

    private void writeEdge(String parentRunId, Map<?, ?> member) {
        Object remoteTaskId = member.get("remoteTaskId");
        if (!(remoteTaskId instanceof String remote) || remote.isBlank()) {
            return;
        }
        String edgeJson = RunTreeRecord.edge(parentRunId, remote,
                text(member.get("toolCallId")).orElse(null), text(member.get("agentName")).orElse(null),
                text(member.get("state")).orElse(null), text(member.get("resultCategory")).orElse(null));
        writer.submit(() -> {
            store.putRecord(RedisTrajectoryStore.runEdgeKey(parentRunId, remote), edgeJson);
            store.putIndex(RedisTrajectoryStore.parentIndexKey(parentRunId, remote));
        });
    }

    private void openRound(Task task, RoundState round, int seq) {
        round.roundSeq = seq;
        round.runId = task.id() + "#" + seq;
        round.startedAt = now();
        round.closedFinal = false;
        round.contextId = task.contextId();
        round.taskId = task.id();
        Optional<TraceContextCarrier.Entry> entry = carrier.find(task.contextId());
        round.traceId = entry.map(TraceContextCarrier.Entry::getTraceId).orElse(null);
        round.tenantId = entry.map(TraceContextCarrier.Entry::getTenantId).orElse(null);
        round.parentRunId = entry.flatMap(TraceContextCarrier.Entry::getParentRunId).orElse(null);
        String nodeJson = RunTreeRecord.nodeOpen(round.runId, "local", round.startedAt,
                round.traceId, round.tenantId, round.parentRunId);
        String runId = round.runId;
        String traceId = round.traceId;
        String contextId = task.contextId();
        writer.submit(() -> {
            store.putRecord(RedisTrajectoryStore.runKey(runId), nodeJson);
            if (traceId != null) {
                store.putIndex(RedisTrajectoryStore.traceIndexKey(traceId, runId));
            }
            if (contextId != null && !contextId.isBlank()) {
                store.putIndex(RedisTrajectoryStore.convIndexKey(contextId, runId));
            }
        });
        carrier.updateCurrentRunId(task.contextId(), runId);
        if (audit != null && task.contextId() != null) {
            boolean degraded = entry.map(TraceContextCarrier.Entry::isDegraded).orElse(false);
            round.auditSeq = audit.openRound(round.tenantId, task.contextId(), task.id(),
                    round.traceId, degraded, round.runId);
            if (seq > 1) {
                audit.recordDecision(round.tenantId, task.contextId(), "approval",
                        Map.of("action", "resume", "runId", round.runId));
            }
        }
    }

    private void closeRound(RoundState round, TaskState finalState) {
        if (round.runId == null || round.closedFinal) {
            // 恢复出的已闭轮节点不再覆写（重复终态 save 不毁历史记录）
            return;
        }
        String nodeJson = RunTreeRecord.nodeClose(round.runId, "local", round.startedAt, now(),
                finalState.name(), round.traceId, round.tenantId, round.parentRunId);
        String runId = round.runId;
        round.closedFinal = true;
        writer.submit(() -> store.putRecord(RedisTrajectoryStore.runKey(runId), nodeJson));
        if (audit != null && round.contextId != null) {
            audit.recordDecision(round.tenantId, round.contextId, "lifecycle",
                    Map.of("to", finalState.name(), "runId", runId));
            if (finalState == TaskState.TASK_STATE_INPUT_REQUIRED
                    || finalState == TaskState.TASK_STATE_AUTH_REQUIRED) {
                audit.recordDecision(round.tenantId, round.contextId, "approval",
                        Map.of("action", "raise", "runId", runId));
            }
            audit.closeRound(round.contextId, round.taskId, finalState.name());
        }
    }

    // 注意：对未知 parent 的 computeIfAbsent 会播种 roundSeq=0 的 RoundState（该 parent
    // 后续真开轮时恰好从 round 1 起算，语义巧合成立）；锁外读 volatile roundSeq 在父并发
    // 推进轮次时可能指向上一轮——边落点偏差一轮属可接受口径，不再加锁放大复杂度。
    private String fullRunId(String parentTaskId) {
        RoundState parentRound = rounds.computeIfAbsent(parentTaskId, this::recoverRound);
        int seq = Math.max(parentRound.roundSeq, 1);
        return parentTaskId + "#" + seq;
    }

    private RoundState recoverRound(String taskId) {
        RoundState recovered = new RoundState();
        if (!store.exists(RedisTrajectoryStore.runKey(taskId + "#1"))) {
            return recovered;
        }
        int count = store.scanRoundKeys(taskId).size();
        recovered.roundSeq = Math.max(count, 1);
        recovered.runId = taskId + "#" + recovered.roundSeq;
        recovered.startedAt = now();
        // 从最近一轮节点读回全部可恢复字段——闭轮覆写时不丢字段；已闭轮节点不再覆写
        store.getRecord(RedisTrajectoryStore.runKey(recovered.runId))
                .flatMap(RunTreeRecord::readNode)
                .ifPresent(node -> {
                    recovered.traceId = node.traceId().orElse(null);
                    recovered.tenantId = node.tenantId().orElse(null);
                    recovered.parentRunId = node.parentRunId().orElse(null);
                    recovered.startedAt = node.startedAt().orElse(recovered.startedAt);
                    recovered.closedFinal = node.finalState().isPresent();
                    // finalState 映回 lastState：重启后从 input-required 续跑才能触发开新轮
                    node.finalState().ifPresent(state -> toTaskState(state)
                            .ifPresent(parsed -> recovered.lastState = parsed));
                });
        return recovered;
    }

    private static Optional<TaskState> toTaskState(String stateName) {
        try {
            return Optional.of(TaskState.valueOf(stateName));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isTerminal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED || state.isInterrupted();
    }

    private static Optional<String> text(Object value) {
        return value instanceof String str ? Optional.of(str) : Optional.empty();
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    /** Per-task round bookkeeping (in-memory; recovered from Redis after restart). */
    private static final class RoundState {
        private volatile int roundSeq;
        private volatile String runId;
        private volatile String startedAt;
        private volatile String traceId;
        private volatile String tenantId;
        private volatile String parentRunId;
        private volatile TaskState lastState;
        private volatile boolean closedFinal;
        private volatile String contextId;
        private volatile String taskId;
        private volatile long auditSeq = -1L;
    }
}
