/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Maps standard OTEL_* environment variables onto {@link OtelTracerConfig}. Environment
 * variables win over the defaults defined here; unsupported-but-set OTEL variables are
 * reported via {@link #unsupportedVariables()} so the assembler can warn about them.
 *
 * <p>Spring 配置（{@code openjiuwen.service.otel.*}）经 overrides 注入，优先级高于
 * OTEL_* 环境变量。
 *
 * @since 2026-08-07
 */
public final class OtelEnvProperties {

    static final String ENV_ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String ENV_PROTOCOL = "OTEL_EXPORTER_OTLP_PROTOCOL";
    static final String ENV_HEADERS = "OTEL_EXPORTER_OTLP_HEADERS";
    static final String ENV_TIMEOUT = "OTEL_EXPORTER_OTLP_TIMEOUT";
    static final String ENV_SERVICE_NAME = "OTEL_SERVICE_NAME";
    static final String ENV_SERVICE_VERSION = "OTEL_SERVICE_VERSION";
    static final String ENV_SAMPLE_RATE = "OTEL_TRACES_SAMPLER_ARG";

    private static final String DEFAULT_PROTOCOL = "grpc";
    private static final String DEFAULT_GRPC_ENDPOINT = "http://localhost:4317";
    private static final String DEFAULT_HTTP_ENDPOINT = "http://localhost:4318";
    private static final String DEFAULT_SERVICE_NAME = "edp-agent";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<String> UNSUPPORTED_VARIABLES = List.of(
            "OTEL_TRACES_SAMPLER", "OTEL_SDK_DISABLED",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
            "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT");

    private final Function<String, String> env;
    private final Function<String, String> overrides;
    private final Map<String, String> headerOverrides;

    /**
     * Creates a reader backed only by OTEL_* environment variables.
     *
     * @param env environment variable source
     */
    public OtelEnvProperties(Function<String, String> env) {
        this(env, null, null);
    }

    /**
     * Creates a reader with Spring configuration overrides layered above the environment.
     *
     * @param env             OTEL_* 环境变量来源
     * @param overrides       Spring 配置覆盖（key 为 OTEL_* 对应的短名，如 endpoint/timeout），可空
     * @param headerOverrides Spring 配置的 headers map，非空时整体替代 OTEL_EXPORTER_OTLP_HEADERS
     */
    public OtelEnvProperties(Function<String, String> env, Function<String, String> overrides,
            Map<String, String> headerOverrides) {
        this.env = env;
        this.overrides = overrides;
        this.headerOverrides = headerOverrides;
    }

    /**
     * Creates a reader backed by {@link System#getenv}.
     *
     * @return environment-backed properties
     */
    public static OtelEnvProperties fromSystemEnv() {
        return new OtelEnvProperties(System::getenv);
    }

    /**
     * Returns the OTLP transport protocol.
     *
     * @return {@code grpc} or {@code http}
     */
    public String getProtocol() {
        String protocol = override("protocol").orElseGet(() -> get(ENV_PROTOCOL, DEFAULT_PROTOCOL));
        if (!"grpc".equals(protocol) && !"http".equals(protocol)) {
            throw new IllegalArgumentException(
                    "unsupported " + ENV_PROTOCOL + " '" + protocol + "', supported: grpc, http");
        }
        return protocol;
    }

    /**
     * Returns the OTLP endpoint; the default follows the configured protocol.
     *
     * @return exporter endpoint URL
     */
    public String getEndpoint() {
        Optional<String> override = override("endpoint");
        if (override.isPresent()) {
            return override.get();
        }
        String defaultEndpoint = "http".equals(getProtocol()) ? DEFAULT_HTTP_ENDPOINT : DEFAULT_GRPC_ENDPOINT;
        return get(ENV_ENDPOINT, defaultEndpoint);
    }

    /**
     * Returns exporter request headers; Spring map overrides replace the env form entirely.
     *
     * @return header map (possibly empty)
     */
    public Map<String, String> getHeaders() {
        if (headerOverrides != null && !headerOverrides.isEmpty()) {
            return new LinkedHashMap<>(headerOverrides);
        }
        String raw = env.apply(ENV_HEADERS);
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return headers;
        }
        for (String segment : raw.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(
                        "illegal " + ENV_HEADERS + " segment '" + trimmed + "', expected key=value");
            }
            headers.put(decode(trimmed.substring(0, eq).trim()), decode(trimmed.substring(eq + 1).trim()));
        }
        return headers;
    }

    /**
     * Returns the export timeout. OTEL 规范定义 OTEL_EXPORTER_OTLP_TIMEOUT 为毫秒整数（如
     * "30000"），优先按此解析；同时兼容 ISO-8601 简写（如 "5S"、"PT30S"）以保持既有配置可用。
     *
     * @return export timeout
     */
    public Duration getTimeout() {
        String raw = override("timeout").orElseGet(() -> env.apply(ENV_TIMEOUT));
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        String trimmed = raw.trim();
        try {
            return Duration.ofMillis(Long.parseLong(trimmed));
        } catch (NumberFormatException e) {
            return Duration.parse(trimmed.startsWith("PT") ? trimmed : "PT" + trimmed);
        }
    }

    /**
     * Returns Resource service.name.
     *
     * @return service name
     */
    public String getServiceName() {
        return override("service-name").orElseGet(() -> get(ENV_SERVICE_NAME, DEFAULT_SERVICE_NAME));
    }

    /**
     * Returns Resource service.version if configured.
     *
     * @return service version, or empty
     */
    public Optional<String> getServiceVersion() {
        Optional<String> override = override("service-version");
        return override.isPresent() ? override : Optional.ofNullable(env.apply(ENV_SERVICE_VERSION));
    }

    /**
     * Returns Resource service.instance.id：Spring 配置优先，回退 OTEL_SERVICE_INSTANCE_ID。
     *
     * @return service instance id, or empty
     */
    public Optional<String> getServiceInstanceId() {
        Optional<String> override = override("service-instance-id");
        return override.isPresent() ? override : Optional.ofNullable(env.apply("OTEL_SERVICE_INSTANCE_ID"));
    }

    /**
     * Returns the trace sample rate in [0.0, 1.0].
     *
     * @return sample rate
     */
    public double getSampleRate() {
        String raw = override("sample-rate").orElseGet(() -> env.apply(ENV_SAMPLE_RATE));
        if (raw == null || raw.isBlank()) {
            return 1.0;
        }
        double rate = Double.parseDouble(raw.trim());
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException(ENV_SAMPLE_RATE + " must be within [0.0, 1.0], got " + rate);
        }
        return rate;
    }

    /**
     * Returns set-but-unsupported OTEL variables, for startup warnings.
     *
     * @return unsupported variable names currently set
     */
    public List<String> unsupportedVariables() {
        List<String> found = new ArrayList<>();
        for (String name : UNSUPPORTED_VARIABLES) {
            if (env.apply(name) != null) {
                found.add(name);
            }
        }
        return found;
    }

    /**
     * Builds the tracer config; redaction is off and truncation disabled per product decision.
     *
     * @return assembled tracer config
     */
    public OtelTracerConfig toTracerConfig() {
        return OtelTracerConfig.builder()
                .exporterType("otlp")
                .exporterEndpoint(getEndpoint())
                .protocol(getProtocol())
                .headers(getHeaders())
                .serviceName(getServiceName())
                .serviceVersion(getServiceVersion().orElse(null))
                .sampleRate(getSampleRate())
                .isRedactionEnabled(false)
                .maxAttrLength(-1)
                .exportTimeoutMs(Math.toIntExact(getTimeout().toMillis()))
                .build();
    }

    private Optional<String> override(String shortName) {
        if (overrides == null) {
            return Optional.empty();
        }
        String value = overrides.apply(shortName);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private String get(String name, String defaultValue) {
        String value = env.apply(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }
}
