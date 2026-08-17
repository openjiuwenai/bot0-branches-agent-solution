/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreExternalProperties;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientDecoratorFactory;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Remote client decorator factory that wraps every remote (A2A downstream agent) client
 * with a {@code sub_agent.dispatch} CLIENT span. The span is opened on the calling thread
 * so it inherits the current tool/chain span as parent.
 *
 * @since 2026-08-10
 */
public class OtelRemoteClientDecoratorFactory implements AgentCoreRemoteClientDecoratorFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(OtelRemoteClientDecoratorFactory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Remote-agent name identifying the versatile service (EDPAgent remote-agents config). */
    private static final String VERSATILE_AGENT_NAME = "versatile-agent";

    private final Tracer tracer;

    /**
     * Creates the factory.
     *
     * @param tracer tracer used for dispatch spans
     */
    public OtelRemoteClientDecoratorFactory(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public RemoteClient decorate(RemoteClientConfig config, RemoteClient delegate,
                                 AgentCoreExternalProperties.RemotePolicy policy) {
        return new OtelRemoteClient(tracer, config, delegate);
    }

    private static final class OtelRemoteClient implements RemoteClient {
        private final Tracer tracer;
        private final RemoteClientConfig config;
        private final RemoteClient delegate;

        private OtelRemoteClient(Tracer tracer, RemoteClientConfig config, RemoteClient delegate) {
            this.tracer = tracer;
            this.config = config;
            this.delegate = delegate;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isStarted() {
            return delegate.isStarted();
        }

        @Override
        public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
            Span span = startDispatchSpan(inputs);
            long startNanos = System.nanoTime();
            // 接口声明 throws Exception——用完成标记 finally 代替 broad catch，异常时统一记 error
            boolean completed = false;
            try (Scope ignored = span.makeCurrent()) {
                Object result = delegate.invoke(inputs, timeoutSeconds);
                span.setAttribute(statusKey(), "completed");
                completed = true;
                if (result != null) {
                    span.setAttribute(summaryKey(), summarize(result));
                }
                return result;
            } finally {
                if (!completed) {
                    span.setAttribute(statusKey(), "error");
                    span.setStatus(StatusCode.ERROR, "error");
                }
                span.setAttribute(elapsedKey(), (System.nanoTime() - startNanos) / 1_000_000L);
                span.end();
            }
        }

        @Override
        public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
            Span span = startDispatchSpan(inputs);
            long startNanos = System.nanoTime();
            boolean opened = false;
            try {
                Iterator<Object> iterator = delegate.stream(inputs, timeoutSeconds);
                span.setAttribute(statusKey(), "streaming");
                opened = true;
                return new SpanEndingIterator(iterator, span, startNanos);
            } finally {
                if (!opened) {
                    span.setAttribute(statusKey(), "error");
                    span.setStatus(StatusCode.ERROR, "error");
                    span.setAttribute(elapsedKey(), (System.nanoTime() - startNanos) / 1_000_000L);
                    span.end();
                }
            }
        }

        /** span 生命周期跟随迭代器：迭代耗尽/中途异常时才结束，覆盖实际流传输时长。 */
        private final class SpanEndingIterator implements Iterator<Object> {
            private final Iterator<Object> iterator;
            private final Span span;
            private final long startNanos;
            private boolean ended;

            SpanEndingIterator(Iterator<Object> iterator, Span span, long startNanos) {
                this.iterator = iterator;
                this.span = span;
                this.startNanos = startNanos;
            }

            @Override
            public boolean hasNext() {
                boolean normalReturn = false;
                try {
                    boolean hasMore = iterator.hasNext();
                    if (!hasMore) {
                        endCompleted();
                    }
                    normalReturn = true;
                    return hasMore;
                } finally {
                    if (!normalReturn) {
                        endWithError();
                    }
                }
            }

            @Override
            public Object next() {
                boolean normalReturn = false;
                try {
                    Object value = iterator.next();
                    normalReturn = true;
                    return value;
                } finally {
                    if (!normalReturn) {
                        endWithError();
                    }
                }
            }

            private void endCompleted() {
                if (ended) {
                    return;
                }
                ended = true;
                span.setAttribute(statusKey(), "completed");
                span.setAttribute(elapsedKey(), (System.nanoTime() - startNanos) / 1_000_000L);
                span.end();
            }

            private void endWithError() {
                if (ended) {
                    return;
                }
                ended = true;
                span.setAttribute(statusKey(), "error");
                span.setStatus(StatusCode.ERROR, "error");
                span.setAttribute(elapsedKey(), (System.nanoTime() - startNanos) / 1_000_000L);
                span.end();
            }
        }

        private boolean isVersatile() {
            return VERSATILE_AGENT_NAME.equals(config.getName()) || VERSATILE_AGENT_NAME.equals(config.getId());
        }

        private String statusKey() {
            return isVersatile() ? "openjiuwen.va.status" : "openjiuwen.subagent.status";
        }

        private String elapsedKey() {
            return isVersatile() ? "openjiuwen.va.elapsed_ms" : "openjiuwen.subagent.elapsed_ms";
        }

        private String summaryKey() {
            return isVersatile() ? "openjiuwen.va.response_summary" : "openjiuwen.subagent.content_summary";
        }

        private Span startDispatchSpan(Map<String, Object> inputs) {
            if (isVersatile()) {
                Span span = tracer.spanBuilder("service.versatile_adapter")
                        .setSpanKind(SpanKind.CLIENT)
                        .setParent(Context.current())
                        .startSpan();
                span.setAttribute("openjiuwen.va.dispatch_mode", "single");
                span.setAttribute("openjiuwen.va.query_intent", asString(inputs, "query_intent").orElse(""));
                span.setAttribute("openjiuwen.va.query_description",
                        asString(inputs, "query_description").orElse(""));
                return span;
            }
            Span span = tracer.spanBuilder("sub_agent.dispatch")
                    .setSpanKind(SpanKind.CLIENT)
                    .setParent(Context.current())
                    .startSpan();
            span.setAttribute("openjiuwen.subagent.entity_id", nullToEmpty(config.getId()));
            span.setAttribute("openjiuwen.subagent.entity_name", nullToEmpty(config.getName()));
            span.setAttribute("openjiuwen.subagent.query", toJson(inputs));
            span.setAttribute("openjiuwen.subagent.sub_agent_url", nullToEmpty(config.getUrl()));
            Optional<String> sessionId = currentSessionId();
            if (sessionId.isPresent()) {
                span.setAttribute("openjiuwen.subagent.context_id",
                        sessionId.get() + "-sub-" + nullToEmpty(config.getId()));
                span.setAttribute("openjiuwen.subagent.sub_task_path",
                        "[\"" + sessionId.get() + "\",\"" + nullToEmpty(config.getId()) + "\"]");
            }
            return span;
        }

        private static Optional<String> currentSessionId() {
            try {
                Session session = SessionContextHolder.getCurrentSession();
                return session != null ? Optional.ofNullable(session.getSessionId()) : Optional.empty();
            } catch (IllegalStateException | NullPointerException | UnsupportedOperationException e) {
                return Optional.empty();
            }
        }

        private static Optional<String> asString(Map<String, Object> inputs, String key) {
            if (inputs == null) {
                return Optional.empty();
            }
            Object value = inputs.get(key);
            return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
        }

        private static String nullToEmpty(String value) {
            return value != null ? value : "";
        }

        private static String toJson(Object value) {
            if (value == null) {
                return "";
            }
            if (value instanceof String str) {
                return str;
            }
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException | IllegalStateException | IllegalArgumentException e) {
                LOGGER.warn("otel dispatch span: serialize failed: {}", e.getClass().getSimpleName());
                return String.valueOf(value);
            }
        }

        private static String summarize(Object result) {
            String json = toJson(result);
            return json.length() <= 512 ? json : json.substring(0, 512);
        }
    }
}
