/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.HttpContextBridge;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.OtelRuntimeSupport;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Decorates the service-layer {@link RemoteAgentCaller} — the channel actually used by
 * EDPAgent-style delegations ({@code RemoteInvocationBatchCoordinator}) — producing the
 * contract {@code sub_agent.dispatch} / {@code service.versatile_adapter} CLIENT spans.
 *
 * <p>The agent-core {@code RemoteClient} SPI channel (covered by
 * {@link OtelRemoteClientDecoratorFactory}) is a different outbound path; both coexist.
 *
 * <p>Calls run on coordinator threads where no OTel context is current, so the parent is
 * resolved from {@link EgressContextStash} (chain context stashed by the rail on the agent
 * thread), falling back to the http root span via {@link HttpContextBridge} — keeping the
 * span on the same trace in both cases.
 *
 * @since 2026-08-17
 */
public class OtelRemoteAgentCallerDecorator implements RemoteAgentCaller {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelRemoteAgentCallerDecorator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Remote-agent name identifying the versatile service (EDPAgent remote-agents config).
     */
    private static final String VERSATILE_AGENT_NAME = "versatile-agent";

    /** 出站 metadata 约定键：parent_run_id（与 TraceIdentityFilter 的入站解析键一致）。 */
    static final String PARENT_RUN_ID_METADATA = "parent_run_id";

    private final RemoteAgentCaller delegate;
    private final Tracer tracer;
    private final A2ARemoteAgentCardRegistry registry;
    private final TraceContextCarrier carrier;

    /**
     * Creates the decorator.
     *
     * @param delegate wrapped caller
     * @param tracer   tracer for dispatch spans
     * @param registry remote agent card registry
     * @param carrier  trace context carrier (may be null; parent_run_id then not injected)
     */
    public OtelRemoteAgentCallerDecorator(RemoteAgentCaller delegate, Tracer tracer,
                                          A2ARemoteAgentCardRegistry registry,
                                          TraceContextCarrier carrier) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.registry = registry;
        this.carrier = carrier;
    }

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
                                                            RemoteAgentCaller.EventObserver eventObserver) {
        boolean versatile = VERSATILE_AGENT_NAME.equals(call.agentName());
        RemoteCall effectiveCall = withParentRunId(call);
        Span span = startSpan(effectiveCall, versatile);
        long startNanos = System.nanoTime();
        // 接口不声明受检异常、实现可抛任意运行时异常——用成功标记 finally 代替 broad catch
        boolean invoked = false;
        // makeCurrent 让 SDK/装饰层在该线程读到 dispatch span 的上下文（SPI header 注入取值为它）
        try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
            CompletableFuture<RemoteCallOutcome> future = delegate.callOutcome(effectiveCall, eventObserver);
            invoked = true;
            future.whenComplete((outcome, error) -> {
                if (error != null) {
                    endWithError(span, startNanos, versatile, error);
                } else {
                    endCompleted(span, startNanos, versatile, outcome);
                }
            });
            return future;
        } finally {
            if (!invoked) {
                endWithError(span, startNanos, versatile,
                        new IllegalStateException("delegate invocation failed"));
            }
        }
    }

    // 重建不可变 RemoteCall，注入本轮 parent_run_id（carrier 无条目/无轮次标记时原样透传）
    private RemoteCall withParentRunId(RemoteCall call) {
        if (carrier == null || call.contextId() == null) {
            return call;
        }
        java.util.Optional<String> parentRunId = carrier.find(call.contextId())
                .flatMap(TraceContextCarrier.Entry::getCurrentRunId);
        if (parentRunId.isEmpty()) {
            return call;
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(call.metadata());
        metadata.put(PARENT_RUN_ID_METADATA, parentRunId.get());
        return new RemoteCall(call.agentName(), call.message(), call.contextId(), call.taskId(),
                metadata, call.messageMetadata(), call.isCallerStreaming());
    }

    private Span startSpan(RemoteCall call, boolean versatile) {
        Optional<Context> parent = resolveParent(call.contextId());
        Span span = tracer.spanBuilder(versatile ? "service.versatile_adapter" : "sub_agent.dispatch")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(parent.orElseGet(Context::root))
                .startSpan();
        if (parent.isEmpty()) {
            LOGGER.warn("otel egress span starts new trace (no stashed/bridge context): agent={}", call.agentName());
        }
        // coordinator 线程无 SessionContextHolder，SessionIdSpanProcessor 注入不到——显式补 session.id
        Optional<String> conversationId = EgressContextStash.findConversationId(call.contextId());
        conversationId.ifPresent(id -> span.setAttribute("session.id", id));
        // dispatch 上下文单独暂存：供 SPI header 注入解析出 dispatch span 的 id（异步发送线程无线程上下文）
        conversationId.ifPresent(id -> EgressContextStash.putOutbound(id, span.storeInContext(Context.root())));
        if (versatile) {
            span.setAttribute("openjiuwen.va.dispatch_mode", "single");
            Map<String, Object> args = parseMessage(call.message());
            span.setAttribute("openjiuwen.va.query_intent",
                    str(args.getOrDefault("query_intent", args.get("intent"))));
            span.setAttribute("openjiuwen.va.query_description",
                    str(args.getOrDefault("query_description", args.get("query"))));
        } else {
            span.setAttribute("openjiuwen.subagent.entity_id", nullToEmpty(call.agentName()));
            span.setAttribute("openjiuwen.subagent.entity_name", nullToEmpty(call.agentName()));
            span.setAttribute("openjiuwen.subagent.query", nullToEmpty(call.message()));
            span.setAttribute("openjiuwen.subagent.sub_agent_url", resolveUrl(call.agentName()));
            span.setAttribute("openjiuwen.subagent.context_id", nullToEmpty(call.contextId()));
            conversationId.ifPresent(id -> span.setAttribute("openjiuwen.subagent.sub_task_path",
                    "[\"" + id + "\",\"" + nullToEmpty(call.agentName()) + "\"]"));
        }
        return span;
    }

    private Optional<Context> resolveParent(String contextId) {
        Optional<Context> stashed = EgressContextStash.find(contextId);
        if (stashed.isPresent()) {
            return stashed;
        }
        Optional<String> conversationId = EgressContextStash.findConversationId(contextId);
        Optional<HttpContextBridge> bridge = OtelRuntimeSupport.bridge();
        if (bridge.isPresent() && conversationId.isPresent()) {
            HttpContextBridge.Entry entry = bridge.get().get(conversationId.get());
            if (entry != null && entry.getSpan() != null) {
                return Optional.of(entry.getSpan().storeInContext(Context.root()));
            }
        }
        return Optional.empty();
    }

    private String resolveUrl(String agentName) {
        if (registry == null || agentName == null) {
            return "";
        }
        try {
            return nullToEmpty(registry.resolveUrl(agentName));
        } catch (IllegalStateException | NullPointerException | IndexOutOfBoundsException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMessage(String message) {
        if (message == null || message.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(message, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Map.of();
        }
    }

    private void endCompleted(Span span, long startNanos, boolean versatile, RemoteCallOutcome outcome) {
        span.setAttribute(key(versatile, "status"), "completed");
        if (outcome != null && outcome.result() != null) {
            String result = outcome.result();
            span.setAttribute(key(versatile, "summary"), result.length() <= 512 ? result : result.substring(0, 512));
        }
        span.setAttribute(key(versatile, "elapsed"), (System.nanoTime() - startNanos) / 1_000_000L);
        span.end();
    }

    private void endWithError(Span span, long startNanos, boolean versatile, Throwable error) {
        boolean canceled = error instanceof CancellationException;
        span.setAttribute(key(versatile, "status"), canceled ? "canceled" : "error");
        span.setStatus(StatusCode.ERROR, error.getMessage() != null ? error.getMessage() : "error");
        span.setAttribute(key(versatile, "elapsed"), (System.nanoTime() - startNanos) / 1_000_000L);
        span.end();
    }

    private static String key(boolean versatile, String kind) {
        String prefix = versatile ? "openjiuwen.va." : "openjiuwen.subagent.";
        return switch (kind) {
            case "status" -> prefix + "status";
            case "elapsed" -> prefix + "elapsed_ms";
            case "summary" -> prefix + (versatile ? "response_summary" : "content_summary");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
