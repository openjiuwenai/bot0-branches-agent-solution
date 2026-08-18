/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Spring 配置风格的 OTel 轨迹配置（{@code openjiuwen.service.otel.*}）。所有项均可选：
 * 未设置的项回退到对应 OTEL_* 环境变量，再回退默认值（见 {@link OtelEnvProperties}）。
 *
 * @since 2026-08-14
 */
@ConfigurationProperties(prefix = "openjiuwen.service.otel")
public class OtelTrajectoryProperties {
    /**
     * 总开关；装配条件直接消费此属性（@ConditionalOnProperty），默认 false。
     */
    private boolean enabled;

    /**
     * OTLP 端点；回退 OTEL_EXPORTER_OTLP_ENDPOINT，再按 protocol 取默认。
     */
    private String endpoint;

    /**
     * 导出协议：grpc / http；回退 OTEL_EXPORTER_OTLP_PROTOCOL，默认 grpc。
     */
    private String protocol;

    /**
     * 导出请求头；yaml map 形态，设置后整体替代 OTEL_EXPORTER_OTLP_HEADERS。
     */
    private Map<String, String> headers;

    /**
     * 导出超时（Spring Duration 风格，如 30s / 500ms）；回退 OTEL_EXPORTER_OTLP_TIMEOUT（毫秒）。
     */
    private Duration timeout;

    /**
     * service.name；回退 OTEL_SERVICE_NAME，默认 edp-agent。
     */
    private String serviceName;

    /**
     * service.version；回退 OTEL_SERVICE_VERSION。
     */
    private String serviceVersion;

    /**
     * service.instance.id；回退 OTEL_SERVICE_INSTANCE_ID。
     */
    private String serviceInstanceId;

    /**
     * 采样率 [0.0, 1.0]；回退 OTEL_TRACES_SAMPLER_ARG，默认 1.0。
     */
    private Double sampleRate;

    /**
     * 获取总开关（是否启用轨迹上报）。
     *
     * @return 总开关（是否启用轨迹上报）
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取OTLP 端点。
     *
     * @return OTLP 端点
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 获取导出协议。
     *
     * @return 导出协议
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 获取导出请求头。
     *
     * @return 导出请求头
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * 获取导出超时。
     *
     * @return 导出超时
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * 获取service.name。
     *
     * @return service.name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 获取service.version。
     *
     * @return service.version
     */
    public String getServiceVersion() {
        return serviceVersion;
    }

    /**
     * 获取service.instance.id。
     *
     * @return service.instance.id
     */
    public String getServiceInstanceId() {
        return serviceInstanceId;
    }

    /**
     * 获取采样率。
     *
     * @return 采样率
     */
    public Double getSampleRate() {
        return sampleRate;
    }

    /**
     * 设置采样率。
     *
     * @param sampleRate 采样率
     */
    public void setSampleRate(Double sampleRate) {
        this.sampleRate = sampleRate;
    }


    /**
     * 设置service.instance.id。
     *
     * @param serviceInstanceId service.instance.id
     */
    public void setServiceInstanceId(String serviceInstanceId) {
        this.serviceInstanceId = serviceInstanceId;
    }


    /**
     * 设置service.version。
     *
     * @param serviceVersion service.version
     */
    public void setServiceVersion(String serviceVersion) {
        this.serviceVersion = serviceVersion;
    }


    /**
     * 设置service.name。
     *
     * @param serviceName service.name
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }


    /**
     * 设置导出超时。
     *
     * @param timeout 导出超时
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }


    /**
     * 设置导出请求头。
     *
     * @param headers 导出请求头
     */
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }


    /**
     * 设置导出协议。
     *
     * @param protocol 导出协议
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }


    /**
     * 设置OTLP 端点。
     *
     * @param endpoint OTLP 端点
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }


    /**
     * 设置总开关（是否启用轨迹上报）。
     *
     * @param enabled 总开关（是否启用轨迹上报）
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
