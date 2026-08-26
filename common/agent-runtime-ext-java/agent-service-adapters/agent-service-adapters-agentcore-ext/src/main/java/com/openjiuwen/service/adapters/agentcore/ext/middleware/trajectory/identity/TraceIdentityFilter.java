/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http.CachedBodyRequest;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http.W3cTraceContextParser;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree.RunTreeRecord;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

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
import java.util.UUID;

/**
 * 第二批标识归一入口 filter：在第一批 http span filter（order=0）之前（更小的负序）
 * 解析入站请求的链路标识并写入 {@link TraceContextCarrier}，供执行树、审计与出站传播复用。
 *
 * <p>提取优先级：① {@code traceparent} header（W3C，复用 {@link W3cTraceContextParser}）；
 * ② body {@code params.metadata} 约定 trace 键（兼容保留，header 为主）；③ 按 contextId 查
 * carrier 既有条目复用其 trace_id（带 taskId 的续跑请求先按 taskId 经 {@link TaskStore} 查回
 * 真实 contextId；miss 时按 taskId 读回首轮节点 {@code runtime:run:{taskId}#1} 的 traceId
 * 重建条目——覆盖进程重启后 carrier 清空的续轮场景）；④ 均无/非法 → 降级生成（W3C 格式
 * 32 位小写 hex）+ degraded=true（putIfAbsent 写入，绝不覆盖并发写入的好条目）。
 *
 * <p>首跳注入：taskId 与 contextId 均缺失时，在 body 的 message 中注入生成的 contextId
 * 再放行（重写 JSON + 更新 Content-Length）——首跳与后续轮共享同一 contextId 与 trace_id；
 * 带 taskId 的续跑请求不注入（SDK strict 校验关闭时 message 会被静默改写，注入反致错键）；
 * 注入失败（非 JSON-RPC 形态）降级为本请求不采集 + WARN。空 body / 非 JSON 一律放行不采集。
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
        CachedBodyRequest wrapped = request instanceof CachedBodyRequest cached
                ? cached : new CachedBodyRequest(request);
        Optional<byte[]> replacement = Optional.empty();
        try {
            replacement = identify(request, new String(wrapped.cachedBody(), StandardCharsets.UTF_8));
        } catch (JsonProcessingException | IllegalStateException e) {
            LOGGER.warn("trace identity extraction failed, request continues without it: {}",
                    e.getClass().getSimpleName());
        }
        HttpServletRequest toPass = replacement
                .<HttpServletRequest>map(body -> new CachedBodyRequest(request, body))
                .orElse(wrapped);
        filterChain.doFilter(toPass, response);
    }

    private Optional<byte[]> identify(HttpServletRequest request, String body) throws JsonProcessingException {
        if (body.isBlank()) {
            return Optional.empty();
        }
        JsonNode root = MAPPER.readTree(body);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        IngressPayload payload = IngressPayload.from(root);
        Optional<String> contextId = payload.contextId();
        Optional<byte[]> replacement = Optional.empty();
        if (contextId.isEmpty() && payload.taskId().isEmpty()) {
            // 首跳双缺失：注入生成的 contextId 再放行（注入失败→本请求不采集）
            if (!(root.path("params").path("message") instanceof ObjectNode messageNode)) {
                LOGGER.warn("trace identity: no JSON-RPC message to inject contextId, skip");
                return Optional.empty();
            }
            String generated = UUID.randomUUID().toString();
            messageNode.put("contextId", generated);
            contextId = Optional.of(generated);
            replacement = Optional.of(MAPPER.writeValueAsBytes(root));
        } else if (contextId.isEmpty()) {
            contextId = resolveContextIdByTask(payload.taskId());
        }
        contextId.ifPresent(id -> writeCarrier(request, id, payload));
        return replacement;
    }

    private void writeCarrier(HttpServletRequest request, String contextId, IngressPayload payload) {
        String key = carrier.resolveKey(contextId).orElse(contextId);
        Optional<String> inbound = W3cTraceContextParser.parseTraceId(request.getHeader("traceparent"))
                .or(payload::metadataTraceId);
        if (inbound.isPresent()) {
            // 上游传入为唯一源：覆盖式写入，继承既有条目的 currentRunId
            TraceContextCarrier.Entry entry = new TraceContextCarrier.Entry(inbound.get(), false,
                    ingressChannel(request), tenantId(request).orElse(null), Instant.now());
            carrier.find(contextId).flatMap(TraceContextCarrier.Entry::getCurrentRunId)
                    .ifPresent(entry::setCurrentRunId);
            payload.parentRunId().ifPresent(entry::setParentRunId);
            carrier.put(key, entry);
            return;
        }
        if (carrier.find(contextId).isPresent()) {
            return;
        }
        TraceContextCarrier.Entry entry = recoverFromRoundOneNode(payload.taskId())
                .map(traceId -> new TraceContextCarrier.Entry(traceId, false,
                        ingressChannel(request), tenantId(request).orElse(null), Instant.now()))
                .orElseGet(() -> new TraceContextCarrier.Entry(generateTraceId(), true,
                        ingressChannel(request), tenantId(request).orElse(null), Instant.now()));
        payload.parentRunId().ifPresent(entry::setParentRunId);
        carrier.putIfAbsent(key, entry);
    }

    private Optional<String> resolveContextIdByTask(Optional<String> taskId) {
        if (taskId.isEmpty() || taskStore == null) {
            return Optional.empty();
        }
        Task task = taskStore.get(taskId.get());
        if (task != null && task.contextId() != null && !task.contextId().isBlank()) {
            return Optional.of(task.contextId());
        }
        // TaskStore 无该 task（含 InMemory 重启清空）时退回 taskId 本身作 carrier key
        return taskId;
    }

    private Optional<String> recoverFromRoundOneNode(Optional<String> taskId) {
        if (taskId.isEmpty() || store == null) {
            return Optional.empty();
        }
        return store.getRecord(RedisTrajectoryStore.runKey(taskId.get() + "#1"))
                .flatMap(RunTreeRecord::readTraceId);
    }

    private static String ingressChannel(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().startsWith("/a2a") ? "a2a" : "rest";
    }

    private static Optional<String> tenantId(HttpServletRequest request) {
        String tenant = request.getHeader(TENANT_HEADER);
        return tenant == null || tenant.isBlank() ? Optional.empty() : Optional.of(tenant);
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
        static IngressPayload from(JsonNode root) {
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
