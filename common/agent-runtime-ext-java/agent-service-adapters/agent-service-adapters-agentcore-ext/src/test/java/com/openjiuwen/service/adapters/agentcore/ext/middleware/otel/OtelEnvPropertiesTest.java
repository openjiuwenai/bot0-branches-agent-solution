/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * OtelEnvProperties 配置解析与覆盖优先级的单元测试。
 */
class OtelEnvPropertiesTest {

    private OtelEnvProperties from(Map<String, String> env) {
        return new OtelEnvProperties(env::get);
    }

    @Test
    void defaults_whenNoEnvSet() {
        OtelEnvProperties props = from(Map.of());
        assertThat(props.getProtocol()).isEqualTo("grpc");
        assertThat(props.getEndpoint()).isEqualTo("http://localhost:4317");
        assertThat(props.getServiceName()).isEqualTo("edp-agent");
        assertThat(props.getSampleRate()).isEqualTo(1.0);
        assertThat(props.getHeaders()).isEmpty();
    }

    @Test
    void endpoint_comesFromEnv() {
        OtelEnvProperties props = from(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317"));
        assertThat(props.getEndpoint()).isEqualTo("http://collector:4317");
    }

    @Test
    void endpointDefault_followsProtocol() {
        OtelEnvProperties props = from(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "http"));
        assertThat(props.getEndpoint()).isEqualTo("http://localhost:4318");
    }

    @Test
    void protocol_invalidValueThrows() {
        assertThatThrownBy(() -> from(Map.of("OTEL_EXPORTER_OTLP_PROTOCOL", "websocket")).getProtocol())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("websocket");
    }

    @Test
    void headers_parsedWithUrlDecodeAndEqualsInValue() {
        OtelEnvProperties props = from(Map.of(
                "OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20tok=en,x-scope = abc ,,"));
        assertThat(props.getHeaders())
                .containsEntry("authorization", "Bearer tok=en")
                .containsEntry("x-scope", "abc")
                .hasSize(2);
    }

    @Test
    void headers_illegalSegmentFailsFast() {
        assertThatThrownBy(() -> from(Map.of("OTEL_EXPORTER_OTLP_HEADERS", "good=1,bad-segment")).getHeaders())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bad-segment");
    }

    @Test
    void sampleRate_outOfRangeThrows() {
        assertThatThrownBy(() -> from(Map.of("OTEL_TRACES_SAMPLER_ARG", "1.5")).getSampleRate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedVars_areReported() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_TRACES_SAMPLER", "always_off");
        env.put("OTEL_SDK_DISABLED", "false");
        OtelEnvProperties props = from(env);
        assertThat(props.unsupportedVariables())
                .containsExactlyInAnyOrder("OTEL_TRACES_SAMPLER", "OTEL_SDK_DISABLED");
    }

    @Test
    void tracerConfig_redactionDisabledAndNoTruncation() {
        OtelEnvProperties props = from(Map.of());
        var config = props.toTracerConfig();
        assertThat(config.isRedactionEnabled()).isFalse();
        assertThat(config.getMaxAttrLength()).isLessThanOrEqualTo(0);
        assertThat(config.getExporterEndpoint()).isEqualTo("http://localhost:4317");
    }

    @Test
    void timeout_otelStandardMillisFormat() {
        // OTEL 规范：OTEL_EXPORTER_OTLP_TIMEOUT 为毫秒整数（DFX-001 测试组 BUG-1 回归）
        OtelEnvProperties props = from(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "30000"));
        assertThat(props.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void timeout_iso8601ShorthandStillWorks() {
        OtelEnvProperties props = from(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "5S"));
        assertThat(props.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
        OtelEnvProperties full = from(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "PT2S"));
        assertThat(full.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
    }

    @Test
    void timeout_defaultIs10Seconds() {
        assertThat(from(Map.of()).getTimeout()).isEqualTo(java.time.Duration.ofSeconds(10));
    }

    @Test
    void springOverrides_winOverEnv() {
        // openjiuwen.service.otel.*（yaml）优先于 OTEL_* 环境变量
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://env-collector:4317");
        env.put("OTEL_SERVICE_NAME", "env-agent");
        env.put("OTEL_EXPORTER_OTLP_TIMEOUT", "9000");
        Map<String, String> overrides = Map.of(
                "endpoint", "http://yaml-collector:4317",
                "service-name", "yaml-agent",
                "timeout", "5000");
        OtelEnvProperties props = new OtelEnvProperties(env::get, overrides::get, null);
        assertThat(props.getEndpoint()).isEqualTo("http://yaml-collector:4317");
        assertThat(props.getServiceName()).isEqualTo("yaml-agent");
        assertThat(props.getTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
    }

    @Test
    void springOverrides_absentFallsBackToEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://env-collector:4317");
        env.put("OTEL_SERVICE_INSTANCE_ID", "env-instance");
        OtelEnvProperties props = new OtelEnvProperties(env::get, key -> null, null);
        assertThat(props.getEndpoint()).isEqualTo("http://env-collector:4317");
        assertThat(props.getServiceInstanceId()).contains("env-instance");
    }

    @Test
    void headerOverrides_replaceEnvHeaders() {
        Map<String, String> env = new HashMap<>();
        env.put("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer%20env");
        OtelEnvProperties props = new OtelEnvProperties(env::get, null,
                Map.of("x-tenant", "t-1"));
        assertThat(props.getHeaders()).containsExactly(Map.entry("x-tenant", "t-1"));
    }

    @Test
    void tracerConfig_wiresTimeoutIntoExportTimeoutMs() {
        // DFX-001 测试组 BUG-2 回归：环境变量超时必须传递进 OtelTracerConfig
        OtelEnvProperties props = from(Map.of("OTEL_EXPORTER_OTLP_TIMEOUT", "5000"));
        assertThat(props.toTracerConfig().getExportTimeoutMs()).isEqualTo(5000);
        assertThat(from(Map.of()).toTracerConfig().getExportTimeoutMs()).isEqualTo(10000);
    }
}
