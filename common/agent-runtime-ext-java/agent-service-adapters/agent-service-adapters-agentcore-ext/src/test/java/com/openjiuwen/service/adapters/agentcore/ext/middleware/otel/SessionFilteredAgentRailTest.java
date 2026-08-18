/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.extensions.tracerotel.OtelRail;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * SessionFilteredAgentRail 会话过滤的单元测试。
 */
class SessionFilteredAgentRailTest {
    /** Records delegated calls without touching any tracer infrastructure. */
    private static final class RecordingRail extends OtelRail {
        private int beforeInvokeCount;
        private int afterInvokeCount;
        private int modelCallCount;

        @Override
        public void beforeInvoke(AgentCallbackContext ctx) {
            beforeInvokeCount++;
        }

        @Override
        public void afterInvoke(AgentCallbackContext ctx) {
            afterInvokeCount++;
        }

        @Override
        public void beforeModelCall(AgentCallbackContext ctx) {
            modelCallCount++;
        }
    }

    private Session sessionOf(String id) {
        return new Session() {
            @Override
            public String getSessionId() {
                return id;
            }

            @Override
            public Object getState(String key) {
                return null;
            }

            @Override
            public void updateState(Map<String, Object> state) {
            }
        };
    }

    private AgentCallbackContext ctxWithSession(String sessionId) {
        return AgentCallbackContext.builder().session(sessionOf(sessionId)).build();
    }

    @Test
    void matchingSession_delegatesAllHooks() {
        RecordingRail delegate = new RecordingRail();
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, null);
        AgentCallbackContext ctx = ctxWithSession("conv-1");
        rail.beforeInvoke(ctx);
        rail.beforeModelCall(ctx);
        rail.afterInvoke(ctx);
        assertThat(delegate.beforeInvokeCount).isEqualTo(1);
        assertThat(delegate.modelCallCount).isEqualTo(1);
        assertThat(delegate.afterInvokeCount).isEqualTo(1);
    }

    @Test
    void foreignSession_isNotDelegated() {
        RecordingRail delegate = new RecordingRail();
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, null);
        AgentCallbackContext foreign = ctxWithSession("conv-2");
        rail.beforeInvoke(foreign);
        rail.beforeModelCall(foreign);
        rail.afterInvoke(foreign);
        assertThat(delegate.beforeInvokeCount).isZero();
        assertThat(delegate.modelCallCount).isZero();
        assertThat(delegate.afterInvokeCount).isZero();
    }

    @Test
    void subSessionDerivedId_matches() {
        RecordingRail delegate = new RecordingRail();
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, null);
        rail.beforeInvoke(ctxWithSession("conv-1_sub_task_ab12cd34"));
        assertThat(delegate.beforeInvokeCount).isEqualTo(1);
    }

    @Test
    void numericSuffixDerivedId_matches() {
        RecordingRail delegate = new RecordingRail();
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, null);
        rail.beforeInvoke(ctxWithSession("conv-1_12"));
        assertThat(delegate.beforeInvokeCount).isEqualTo(1);
    }

    @Test
    void nullSession_isNotDelegatedAndDoesNotThrow() {
        RecordingRail delegate = new RecordingRail();
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, null);
        AgentCallbackContext ctx = AgentCallbackContext.builder().build();
        assertThatCode(() -> rail.beforeInvoke(ctx)).doesNotThrowAnyException();
        assertThat(delegate.beforeInvokeCount).isZero();
    }

    @Test
    void priority_isZero() {
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", new RecordingRail(), null);
        assertThat(rail.getPriority()).isZero();
    }

    @Test
    void bridge_makesHttpSpanCurrentDuringDelegate() {
        Span httpSpan = Span.wrap(SpanContext.create(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb",
                io.opentelemetry.api.trace.TraceFlags.getSampled(),
                io.opentelemetry.api.trace.TraceState.getDefault()));
        final SpanContext[] seen = new SpanContext[1];
        OtelRail delegate = new OtelRail() {
            @Override
            public void beforeInvoke(AgentCallbackContext ctx) {
                seen[0] = Span.fromContext(Context.current()).getSpanContext();
            }
        };
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", delegate, httpSpan);
        AgentCallbackContext ctx = ctxWithSession("conv-1");
        rail.beforeInvoke(ctx);
        assertThat(seen[0].getTraceId()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        rail.afterInvoke(ctx);
        assertThat(Span.fromContext(Context.current()).getSpanContext().isValid()).isFalse();
    }

    @Test
    void delegateException_isSwallowedWithWarn() {
        OtelRail failing = new OtelRail() {
            @Override
            public void beforeInvoke(AgentCallbackContext ctx) {
                throw new IllegalStateException("boom");
            }
        };
        SessionFilteredAgentRail rail = new SessionFilteredAgentRail("conv-1", failing, null);
        assertThatCode(() -> rail.beforeInvoke(ctxWithSession("conv-1"))).doesNotThrowAnyException();
    }
}
