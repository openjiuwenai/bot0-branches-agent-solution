/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行树采集装饰器：包装任意 {@link TaskStore} Bean（{@code WriteThrottlingTaskStore} 外侧，
 * 能看到含节流丢弃前的全部 save），在 save 时识别三类写入并异步落执行树记录：
 *
 * <ul>
 *   <li>新 Task 首次 save（seen-set 判定）→ 开第 1 轮节点（run_id={taskId}#1, kind=local）；
 *   重启后以 {@code runtime:run:{taskId}#1} 节点键存在性补位并恢复 roundSeq（扫描节点键计数）。</li>
 *   <li>已有 Task 从 input-required 回到 working → roundSeq+1 开新轮节点。</li>
 *   <li>影子 Task（id 前缀 {@code shadow:}，metadata 含 {@code _remote_batch} 快照）→ 按快照
 *   每个 member 产委托边（parent_run_id 全格式 → member.remoteTaskId）。</li>
 *   <li>终态迁移（completed/failed/canceled/input-required/auth-required）→ 当前轮节点收尾
 *   （endedAt、finalState）。</li>
 * </ul>
 *
 * <p>开轮时回填 carrier 的 currentRunId（供出站装饰器读取）；全部记录写经
 * {@link AsyncTrajectoryWriter} 异步刷写，任何环节失败不阻塞、不影响 save 主流程。
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
    private final Map<String, RoundState> rounds = new ConcurrentHashMap<>();

    /**
     * Creates the decorator.
     *
     * @param delegate upstream task store
     * @param carrier  trace context carrier
     * @param store    trajectory record store
     * @param writer   async writer
     */
    public RunTreeTaskStoreDecorator(TaskStore delegate, TraceContextCarrier carrier,
                                     RedisTrajectoryStore store, AsyncTrajectoryWriter writer) {
        this.delegate = delegate;
        this.carrier = carrier;
        this.store = store;
        this.writer = writer;
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
        TaskState current = task.status() != null ? task.status().state() : null;
        if (round.roundSeq == 0) {
            openRound(task, round, 1);
        } else if (current == TaskState.TASK_STATE_WORKING && round.lastState != null
                && round.lastState.isInterrupted()) {
            openRound(task, round, round.roundSeq + 1);
        }
        if (current != null && isTerminal(current)) {
            closeRound(task, round, current);
        }
        round.lastState = current;
    }

    private void observeShadow(Task task) {
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

    private void writeEdge(String parentRunId, Map<?, ?> member) {
        Object remoteTaskId = member.get("remoteTaskId");
        if (!(remoteTaskId instanceof String remote) || remote.isBlank()) {
            return;
        }
        String edgeJson = RunTreeRecord.edge(parentRunId, remote,
                text(member.get("toolCallId")), text(member.get("agentName")),
                text(member.get("state")), text(member.get("resultCategory")));
        writer.submit(() -> {
            store.putRecord(RedisTrajectoryStore.runEdgeKey(parentRunId, remote), edgeJson);
            store.putIndex(RedisTrajectoryStore.parentIndexKey(parentRunId, remote));
        });
    }

    private void openRound(Task task, RoundState round, int seq) {
        round.roundSeq = seq;
        round.runId = task.id() + "#" + seq;
        round.startedAt = now();
        round.traceId = carrier.find(task.contextId())
                .map(TraceContextCarrier.Entry::getTraceId).orElse(null);
        round.tenantId = carrier.find(task.contextId())
                .map(TraceContextCarrier.Entry::getTenantId).orElse(null);
        String nodeJson = RunTreeRecord.nodeOpen(round.runId, "local", round.startedAt,
                round.traceId, round.tenantId);
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
    }

    private void closeRound(Task task, RoundState round, TaskState finalState) {
        if (round.runId == null) {
            return;
        }
        String nodeJson = RunTreeRecord.nodeClose(round.runId, "local", round.startedAt, now(),
                finalState.name(), round.traceId, round.tenantId);
        String runId = round.runId;
        writer.submit(() -> store.putRecord(RedisTrajectoryStore.runKey(runId), nodeJson));
    }

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
        int count = store.scan(RedisTrajectoryStore.runKey(taskId + "#") + "*").size();
        recovered.roundSeq = Math.max(count, 1);
        recovered.runId = taskId + "#" + recovered.roundSeq;
        recovered.startedAt = now();
        return recovered;
    }

    private static boolean isTerminal(TaskState state) {
        return state == TaskState.TASK_STATE_COMPLETED || state == TaskState.TASK_STATE_FAILED
                || state == TaskState.TASK_STATE_CANCELED || state.isInterrupted();
    }

    private static String text(Object value) {
        return value instanceof String str ? str : null;
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(ISO);
    }

    /** Per-task round bookkeeping (in-memory; recovered from Redis after restart). */
    private static final class RoundState {
        private int roundSeq;
        private String runId;
        private String startedAt;
        private String traceId;
        private String tenantId;
        private TaskState lastState;
    }
}
