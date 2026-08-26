/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * TurnIndexSpanProcessor 的单元测试：chain span 写 cascade.turn_index、非 chain 不写、
 * carrier 缺失 no-op。
 */
class TurnIndexSpanProcessorTest {
    @AfterEach
    void cleanup() {
        SessionContextHolder.clearCurrentSession();
    }

    @Test
    void writesTurnIndexOnChainSpan() {
        TraceContextCarrier carrier = TraceContextCarrier.create(86400L);
        TraceContextCarrier.Entry entry = new TraceContextCarrier.Entry(
                "t", false, "a2a", "tenant", Instant.now());
        carrier.put("conv-1", entry);
        carrier.updateCurrentRunId("conv-1", "task-1#3");
        Session session = mock(Session.class);
        when(session.getSessionId()).thenReturn("conv-1");
        SessionContextHolder.setCurrentSession(session);

        ReadWriteSpan span = mock(ReadWriteSpan.class);
        when(span.getName()).thenReturn("chain.EDPAgent");
        new TurnIndexSpanProcessor(() -> carrier).onStart(Context.root(), span);
        org.mockito.Mockito.verify(span).setAttribute("cascade.turn_index", 3L);
    }

    @Test
    void skipsNonChainSpanAndMissingCarrier() {
        ReadWriteSpan span = mock(ReadWriteSpan.class);
        when(span.getName()).thenReturn("llm.model-x");
        new TurnIndexSpanProcessor(() -> null).onStart(Context.root(), span);
        org.mockito.Mockito.verify(span, org.mockito.Mockito.never())
                .setAttribute(org.mockito.ArgumentMatchers.eq("cascade.turn_index"),
                        org.mockito.ArgumentMatchers.anyLong());
    }
}
