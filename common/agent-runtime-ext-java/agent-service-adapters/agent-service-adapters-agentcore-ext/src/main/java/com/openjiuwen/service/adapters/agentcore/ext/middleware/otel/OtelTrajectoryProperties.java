/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring 配置风格的 OTel 轨迹配置（{@code openjiuwen.service.otel.*}）。所有项均可选：
 * 未设置的项回退到对应 OTEL_* 环境变量，再回退默认值（见 {@link OtelEnvProperties}）。
 *
 * @since 2026-08-14
 */
@ConfigurationProperties(prefix = "openjiuwen.service.otel")
public class OtelTrajectoryProperties {

    /** 总开关；装配条件直接消费此属性（@ConditionalOnProperty），默认 false。 */
    private boolean enabled;

    /** OTLP 端点；回退 OTEL_EXPORTER_OTLP_ENDPOINT，再按 protocol 取默认。 */
    private String endpoint;

    /** 导出协议：grpc / http；回退 OTEL_EXPORTER_OTLP_PROTOCOL，默认 grpc。 */
    private String protocol;

    /** 导出请求头；yaml map 形态，设置后整体替代 OTEL_EXPORTER_OTLP_HEADERS。 */
    private Map<String, String> headers;

    /** 导出超时（Spring Duration 风格，如 30s / 500ms）；回退 OTEL_EXPORTER_OTLP_TIMEOUT（毫秒）。 */
    private Duration timeout;

    /** service.name；回退 OTEL_SERVICE_NAME，默认 edp-agent。 */
    private String serviceName;

    /** service.version；回退 OTEL_SERVICE_VERSION。 */
    private String serviceVersion;

    /** service.instance.id；回退 OTEL_SERVICE_INSTANCE_ID。 */
    private String serviceInstanceId;

    /** 采样率 [0.0, 1.0]；回退 OTEL_TRACES_SAMPLER_ARG，默认 1.0。 */
    private Double sampleRate;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }

    public String getServiceInstanceId() {
        return serviceInstanceId;
    }

    public void setServiceInstanceId(String serviceInstanceId) {
        this.serviceInstanceId = serviceInstanceId;
    }

    public Double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(Double sampleRate) {
        this.sampleRate = sampleRate;
    }
}
