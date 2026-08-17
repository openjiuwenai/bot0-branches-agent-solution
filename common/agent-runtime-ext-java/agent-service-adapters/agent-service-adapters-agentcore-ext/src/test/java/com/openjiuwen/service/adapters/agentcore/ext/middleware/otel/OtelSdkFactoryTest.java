/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

class OtelSdkFactoryTest {

    private OtelTracerConfig config() {
        return OtelTracerConfig.builder()
                .exporterType("otlp")
                .exporterEndpoint("http://localhost:4317")
                .serviceName("edp-agent")
                .isRedactionEnabled(false)
                .maxAttrLength(-1)
                .build();
    }

    @Test
    void provider_resourceContainsServiceNameAndInstanceId() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config(), exporter, "inst-1");
        provider.get("test").spanBuilder("probe").startSpan().end();
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        var resource = exporter.getFinishedSpanItems().get(0).getResource();
        assertThat(resource.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")))
                .isEqualTo("edp-agent");
        assertThat(resource.getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.instance.id")))
                .isEqualTo("inst-1");
        provider.close();
    }

    @Test
    void provider_spansFlowToExporter() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config(), exporter, "inst-1");
        var tracer = provider.get("test");
        var span = tracer.spanBuilder("unit").startSpan();
        span.end();
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exporter.getFinishedSpanItems())
                .extracting(SpanData::getName)
                .containsExactly("unit");
        provider.close();
    }

    @Test
    void createProvider_generatesInstanceIdWhenAbsent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config(), exporter, null);
        provider.get("test").spanBuilder("probe").startSpan().end();
        provider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
        String instanceId = exporter.getFinishedSpanItems().get(0).getResource()
                .getAttribute(io.opentelemetry.api.common.AttributeKey.stringKey("service.instance.id"));
        assertThat(instanceId).isNotBlank();
        provider.close();
    }
}
