/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import com.openjiuwen.service.app.controller.a2a.client.A2AOutboundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the W3C {@code traceparent} value for outbound A2A calls. Primary source is
 * the span current on the calling thread (the dispatch decorator makes its span current
 * around the delegate call); fallback is the per-session chain context stashed by the
 * rail (coordinator threads carry no OTel context, so the correlation key is read from
 * the JSON-RPC body).
 *
 * <p>{@link #provideHeaders(A2AOutboundRequest)} implements the main-repo
 * {@code A2APropagationHeaderRegistry} provider contract and is registered there by
 * the OTel auto-configuration (option-1 channel; the A2AHttpClientProvider SPI
 * registration is retired).
 *
 * @since 2026-08-20
 */
public final class TraceparentResolver {

    static final String TRACEPARENT_HEADER = "traceparent";

    private static final String VERSION_FLAGS = "01";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceparentResolver() {
    }

    /**
     * Computes the propagation headers for one outbound A2A request (main-repo
     * {@code A2APropagationHeaderProvider} contract). Prefers the span current on the
     * executing thread; when absent (async send threads), parses the message
     * {@code contextId} from the JSON-RPC body and looks the context up in the egress
     * stash. Never throws: malformed input simply yields no headers.
     *
     * @param request outbound request coordinates (body may be null for GET/DELETE)
     * @return headers to inject (empty when no trace context is available)
     */
    public static Map<String, String> provideHeaders(A2AOutboundRequest request) {
        Optional<String> value = resolve();
        if (value.isEmpty()) {
            value = extractContextId(request.body()).flatMap(TraceparentResolver::resolveFromStash);
        }
        return value.map(v -> Map.of(TRACEPARENT_HEADER, v)).orElse(Map.of());
    }

    private static Optional<String> extractContextId(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode message = MAPPER.readTree(body).path("params").path("message");
            JsonNode id = message.get("contextId");
            if (id != null && id.isTextual() && !id.asText().isBlank()) {
                return Optional.of(id.asText());
            }
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Resolves the current traceparent, if any valid span context is available.
     *
     * @return W3C traceparent string, or empty when nothing to propagate
     */
    public static Optional<String> resolve() {
        Optional<SpanContext> current = valid(Span.current().getSpanContext());
        if (current.isPresent()) {
            return current.map(TraceparentResolver::format);
        }
        return Optional.empty();
    }

    /**
     * Resolves the traceparent for a remote-context id via the egress stash (used when
     * the calling thread has no current span, e.g. coordinator callback threads).
     *
     * @param contextId remote context id (bare or combined form)
     * @return W3C traceparent string, or empty
     */
    public static Optional<String> resolveFromStash(String contextId) {
        // 优先 dispatch 上下文（B 侧树挂 dispatch 下）；无则退回 chain 上下文
        Optional<Context> ctx = EgressContextStash.findOutbound(contextId);
        if (ctx.isEmpty()) {
            ctx = EgressContextStash.find(contextId);
        }
        return ctx.map(c -> Span.fromContext(c).getSpanContext())
                .flatMap(sc -> valid(sc).map(TraceparentResolver::format));
    }

    private static Optional<SpanContext> valid(SpanContext ctx) {
        return ctx != null && ctx.isValid() ? Optional.of(ctx) : Optional.empty();
    }

    private static String format(SpanContext ctx) {
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + VERSION_FLAGS;
    }
}
