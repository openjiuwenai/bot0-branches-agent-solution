/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionIdSpanProcessorTest {

    @AfterEach
    void clearHolder() {
        SessionContextHolder.setCurrentSession(null);
    }

    private SdkTracerProvider providerWith(InMemorySpanExporter exporter) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(new SessionIdSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
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

    @Test
    void onStart_writesSessionIdFromHolder() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = providerWith(exporter);
        SessionContextHolder.setCurrentSession(sessionOf("conv-1"));
        provider.get("test").spanBuilder("s").startSpan().end();
        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.stringKey("session.id"))).isEqualTo("conv-1");
        provider.close();
    }

    @Test
    void onStart_noSessionBound_noAttribute() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = providerWith(exporter);
        provider.get("test").spanBuilder("s").startSpan().end();
        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes()
                .get(AttributeKey.stringKey("session.id"))).isNull();
        provider.close();
    }

    @Test
    void onStart_spanRemainsWritable() {
        SessionContextHolder.setCurrentSession(sessionOf("conv-2"));
        SessionIdSpanProcessor processor = new SessionIdSpanProcessor();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build();
        ReadWriteSpan span = (ReadWriteSpan) provider.get("t").spanBuilder("x").startSpan();
        assertThat(span.getAttribute(AttributeKey.stringKey("session.id"))).isEqualTo("conv-2");
        span.end();
        provider.close();
    }

    @Test
    void processor_requiresStartOnly() {
        SessionIdSpanProcessor processor = new SessionIdSpanProcessor();
        assertThat(processor.isStartRequired()).isTrue();
        assertThat(processor.isEndRequired()).isFalse();
    }
}
