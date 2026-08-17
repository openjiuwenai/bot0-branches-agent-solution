/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.openjiuwen.core.session.tracer.TracerHandlerRegistry;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * OtelAgentRegistrar handler 注册的单元测试。
 */
class OtelAgentRegistrarTest {

    @AfterEach
    void cleanup() {
        TracerHandlerRegistry.unregisterHandler("otel_agent");
    }

    private OtelTracerConfig config() {
        return OtelTracerConfig.builder().exporterType("console").isRedactionEnabled(false).build();
    }

    private io.opentelemetry.api.trace.Tracer tracer() {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(InMemorySpanExporter.create()))
                .build().get("test");
    }

    @Test
    void register_installsJsonHandler() {
        new OtelAgentRegistrar(tracer(), config()).register();
        assertThat(TracerHandlerRegistry.getAgentHandlers().get("otel_agent"))
                .isInstanceOf(OtelJsonAgentHandler.class);
    }

    @Test
    void register_twiceReplacesWithoutThrowing() {
        OtelAgentRegistrar registrar = new OtelAgentRegistrar(tracer(), config());
        registrar.register();
        assertThatCode(registrar::register).doesNotThrowAnyException();
        assertThat(TracerHandlerRegistry.getAgentHandlers().get("otel_agent"))
                .isInstanceOf(OtelJsonAgentHandler.class);
    }

    @Test
    void register_failureIsWarnOnly() {
        OtelAgentRegistrar registrar = new OtelAgentRegistrar(null, config());
        assertThatCode(registrar::register).doesNotThrowAnyException();
    }
}
