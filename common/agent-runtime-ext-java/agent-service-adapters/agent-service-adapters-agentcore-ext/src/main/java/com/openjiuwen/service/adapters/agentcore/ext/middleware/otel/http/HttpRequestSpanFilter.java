/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.HttpContextBridge;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
        CachedBodyRequest wrapped = new CachedBodyRequest(request);
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
                .setParent(extractParent(request))
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
                    endSpan(span, response, conversationId);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    endSpan(span, response, conversationId);
                }

                @Override
                public void onError(AsyncEvent event) {
                    endSpan(span, response, conversationId);
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // nothing to do on re-dispatch
                }
            });
            return;
        }
        endSpan(span, response, conversationId);
    }

    private void endSpan(Span span, HttpServletResponse response, String conversationId) {
        try {
            span.setAttribute("http.response.status_code", (long) response.getStatus());
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

    private static Context extractParent(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent == null) {
            return Context.root();
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4) {
            return Context.root();
        }
        try {
            return Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(
                    parts[1], parts[2], TraceFlags.fromHex(parts[3], 0), TraceState.getDefault())));
        } catch (IllegalArgumentException | NullPointerException | IllegalStateException e) {
            LOGGER.warn("otel filter: illegal traceparent ignored");
            return Context.root();
        }
    }

    /** Request wrapper that caches the body once and replays it to downstream consumers. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] cachedBody() {
            return body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // synchronous replay; listener not needed
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
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
