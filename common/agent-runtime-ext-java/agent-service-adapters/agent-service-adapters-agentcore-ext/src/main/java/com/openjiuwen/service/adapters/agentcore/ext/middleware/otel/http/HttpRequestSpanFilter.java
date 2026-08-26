/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.HttpContextBridge;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Entry filter that produces the {@code http.request} SERVER root span for every agent
 * request. The request body is cached so the downstream controller can still consume it;
 * the span is carried to the loop thread via {@link HttpContextBridge}. On SSE requests
 * the span ends with the async response (AsyncListener), on sync requests it ends when
 * the filter chain returns.
 *
 * @since 2026-08-07
 */
public class HttpRequestSpanFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequestSpanFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tracer tracer;
    private final HttpContextBridge bridge;

    public HttpRequestSpanFilter(Tracer tracer, HttpContextBridge bridge) {
        this.tracer = tracer;
        this.bridge = bridge;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyRequest wrapped = request instanceof CachedBodyRequest cached
                ? cached : new CachedBodyRequest(request);
        String body = new String(wrapped.cachedBody(), StandardCharsets.UTF_8);
        Optional<String> parsedConversationId = parseConversationId(body);
        if (parsedConversationId.isEmpty()) {
            LOGGER.warn("otel trajectory disabled for request: no conversationId in body");
            filterChain.doFilter(wrapped, response);
            return;
        }
        String conversationId = parsedConversationId.get();

        Span span = tracer.spanBuilder("http.request")
                .setSpanKind(SpanKind.SERVER)
                .setParent(W3cTraceContextParser.parseToContext(request.getHeader("traceparent")))
                .startSpan();
        span.setAttribute("http.request.method", request.getMethod());
        span.setAttribute("http.route", request.getRequestURI());
        span.setAttribute("session.id", conversationId);
        span.setAttribute("openjiuwen.http.request_body", body);
        bridge.put(conversationId, new HttpContextBridge.Entry(span));

        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            finish(request, response, conversationId, span);
        }
    }

    private void finish(HttpServletRequest request, HttpServletResponse response,
                        String conversationId, Span span) {
        if (request.isAsyncStarted()) {
            request.getAsyncContext().addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    endSpan(span, response, conversationId, "complete");
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    endSpan(span, response, conversationId, "timeout");
                }

                @Override
                public void onError(AsyncEvent event) {
                    endSpan(span, response, conversationId, "error");
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // nothing to do on re-dispatch
                }
            });
            return;
        }
        endSpan(span, response, conversationId, "complete");
    }

    private void endSpan(Span span, HttpServletResponse response, String conversationId, String outcome) {
        try {
            long statusCode = (long) response.getStatus();
            span.setAttribute("http.response.status_code", statusCode);
            // 响应摘要（合法 JSON，合同口径：限模式——SSE 响应体流式写出不缓存，摘要含状态码与完成原因）
            span.setAttribute("openjiuwen.http.response_summary",
                    "{\"status\":" + statusCode + ",\"outcome\":\"" + outcome + "\"}");
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            LOGGER.warn("otel http span status write failed: {}", e.getClass().getSimpleName());
        } finally {
            span.end();
            bridge.remove(conversationId);
            // 出站父上下文暂存随会话收尾一并清理（详见 SessionFilteredAgentRail.afterInvoke 注释）
            com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress.EgressContextStash
                    .remove(conversationId);
        }
    }

    private static Optional<String> parseConversationId(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode id = node.get("conversationId");
            if (id == null) {
                id = node.get("conversation_id");
            }
            if (id == null) {
                // A2A JSON-RPC shape: params.message.contextId (mapped to conversationId downstream)
                JsonNode message = node.path("params").path("message");
                id = message.get("contextId");
            }
            return id != null && id.isTextual() && !id.asText().isBlank()
                    ? Optional.of(id.asText()) : Optional.empty();
        } catch (JsonProcessingException | IllegalStateException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
