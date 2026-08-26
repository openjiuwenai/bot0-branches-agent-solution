/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http.CachedBodyRequest;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http.W3cTraceContextParser;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/**
 * 第二批标识归一入口 filter：在第一批 http span filter（order=0）之前（更小的负序）
 * 解析入站请求的链路标识并写入 {@link TraceContextCarrier}，供执行树、审计与出站传播复用。
 *
 * <p>提取优先级：① {@code traceparent} header（W3C，复用 {@link W3cTraceContextParser}）；
 * ② body {@code params.metadata} 约定 trace 键（兼容保留，header 为主）；③ 按 contextId 查
 * carrier 既有条目复用其 trace_id（带 taskId 的续跑请求先按 taskId 经 {@link TaskStore} 查回
 * 真实 contextId；miss 时按 taskId 读回首轮节点 {@code runtime:run:{taskId}#1} 的 traceId
 * 重建条目——覆盖进程重启后 carrier 清空的续轮场景）；④ 均无/非法 → 降级生成（W3C 格式
 * 32 位小写 hex）+ degraded=true。任何环节失败只 WARN 放行，不影响请求处理。
 *
 * @since 2026-08-26
 */
public class TraceIdentityFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceIdentityFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TENANT_HEADER = "x-tenant-id";

    private final TraceContextCarrier carrier;
    private final TaskStore taskStore;
    private final RedisTrajectoryStore store;

    /**
     * Creates the filter.
     *
     * @param carrier   trace context carrier
     * @param taskStore SDK task store (may be null; taskId recovery then skipped)
     * @param store     trajectory store (may be null; restart recovery then skipped)
     */
    public TraceIdentityFilter(TraceContextCarrier carrier, TaskStore taskStore, RedisTrajectoryStore store) {
        this.carrier = carrier;
        this.taskStore = taskStore;
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyRequest wrapped = new CachedBodyRequest(request);
        try {
            identify(request, new String(wrapped.cachedBody(), StandardCharsets.UTF_8));
        } catch (IllegalStateException | JsonProcessingException e) {
            LOGGER.warn("trace identity extraction failed, request continues without it: {}",
                    e.getClass().getSimpleName());
        }
        filterChain.doFilter(wrapped, response);
    }

    private void identify(HttpServletRequest request, String body) throws JsonProcessingException {
        IngressPayload payload = IngressPayload.parse(body);
        String contextId = payload.contextId.orElseGet(() -> resolveContextIdByTask(payload.taskId));
        if (contextId == null) {
            return;
        }
        String traceId = W3cTraceContextParser.parseTraceId(request.getHeader("traceparent"))
                .orElseGet(() -> payload.metadataTraceId.orElseGet(() -> reuseOrRecover(contextId, payload.taskId)));
        boolean degraded = traceId == null;
        TraceContextCarrier.Entry entry = carrier.find(contextId)
                .filter(existing -> !degraded && existing.getTraceId().equals(traceId))
                .orElseGet(() -> new TraceContextCarrier.Entry(
                        degraded ? generateTraceId() : traceId, degraded,
                        ingressChannel(request), tenantId(request), Instant.now()));
        payload.parentRunId.ifPresent(entry::setParentRunId);
        carrier.put(contextId, entry);
    }

    private String reuseOrRecover(String contextId, Optional<String> taskId) {
        Optional<TraceContextCarrier.Entry> existing = carrier.find(contextId);
        if (existing.isPresent()) {
            return existing.get().getTraceId();
        }
        if (taskId.isPresent() && store != null) {
            Optional<String> recovered = recoverFromRoundOneNode(taskId.get());
            if (recovered.isPresent()) {
                return recovered.get();
            }
        }
        return null;
    }

    private String resolveContextIdByTask(Optional<String> taskId) {
        if (taskId.isEmpty() || taskStore == null) {
            return null;
        }
        Task task = taskStore.get(taskId.get());
        if (task != null && task.contextId() != null && !task.contextId().isBlank()) {
            return task.contextId();
        }
        // 重启恢复：TaskStore 无该 task（含 InMemory 重启清空）时退回 taskId 本身作 carrier key
        return taskId.get();
    }

    private Optional<String> recoverFromRoundOneNode(String taskId) {
        Optional<String> node = store.getRecord(RedisTrajectoryStore.runKey(taskId + "#1"));
        if (node.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode traceId = MAPPER.readTree(node.get()).get("traceId");
            return traceId != null && traceId.isTextual() ? Optional.of(traceId.asText()) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String ingressChannel(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().startsWith("/a2a") ? "a2a" : "rest";
    }

    private static String tenantId(HttpServletRequest request) {
        String tenant = request.getHeader(TENANT_HEADER);
        return tenant == null || tenant.isBlank() ? null : tenant;
    }

    private static String generateTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 入站 JSON-RPC body 的标识字段视图。 */
    private record IngressPayload(Optional<String> contextId, Optional<String> taskId,
                                  Optional<String> metadataTraceId, Optional<String> parentRunId) {
        static IngressPayload parse(String body) throws JsonProcessingException {
            JsonNode root = MAPPER.readTree(body);
            JsonNode message = root.path("params").path("message");
            JsonNode metadata = root.path("params").path("metadata");
            return new IngressPayload(
                    text(message.get("contextId")).or(() -> text(root.get("conversationId"))),
                    text(message.get("taskId")),
                    text(metadata.get("trace_id")).or(() -> text(metadata.get("traceId"))),
                    text(metadata.get("parent_run_id")).or(() -> text(metadata.get("parentRunId"))));
        }

        private static Optional<String> text(JsonNode node) {
            return node != null && node.isTextual() && !node.asText().isBlank()
                    ? Optional.of(node.asText()) : Optional.empty();
        }
    }
}
