/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

import org.junit.jupiter.api.Test;

/**
 * HttpContextBridge 请求级载体的单元测试。
 */
class HttpContextBridgeTest {

    private Span spanOf(String traceId) {
        return Span.wrap(SpanContext.create(traceId, "bbbbbbbbbbbbbbbb",
                TraceFlags.getSampled(), TraceState.getDefault()));
    }

    @Test
    void putGetRemove_roundTrip() {
        HttpContextBridge bridge = new HttpContextBridge();
        HttpContextBridge.Entry entry = new HttpContextBridge.Entry(spanOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        bridge.put("conv-1", entry);
        assertThat(bridge.get("conv-1")).isSameAs(entry);
        bridge.remove("conv-1");
        assertThat(bridge.get("conv-1")).isNull();
    }

    @Test
    void entry_exposesSpanForDirectWrite() {
        HttpContextBridge.Entry entry = new HttpContextBridge.Entry(spanOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertThat(entry.getSpan().getSpanContext().getTraceId()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        entry.getSpan().setAttribute("openjiuwen.trace.id", "uuid-1");
        assertThat(entry.getSpan()).isNotNull();
    }

    @Test
    void get_missingConversation_returnsNull() {
        assertThat(new HttpContextBridge().get("nope")).isNull();
    }
}
