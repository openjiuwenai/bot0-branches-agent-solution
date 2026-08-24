/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.openjiuwen.edp.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DFX-001 OTLP 依赖加载验证测试。
 *
 * <p>验证删除 ext 依赖上的 opentelemetry-exporter-otlp exclusion 后，
 * OTLP 相关类仍可通过直接声明路径正常加载到 classpath。
 * 若任一 Class.forName 抛出 ClassNotFoundException，说明 OTLP 依赖缺失，
 * otel.enabled=true 时将触发 NoClassDefFoundError。</p>
 *
 * <p>覆盖范围（基于 Maven 依赖树验证的实际版本 opentelemetry 1.55.0）：</p>
 * <ul>
 *   <li>opentelemetry-exporter-otlp — OtlpGrpcSpanExporter / OtlpHttpSpanExporter</li>
 *   <li>opentelemetry-exporter-sender-okhttp — OkHttpGrpcSender / OkHttpHttpSender（1.55.0 传递 sender）</li>
 *   <li>agent-core-java tracerotel 扩展 — OtelTracerSetup（被 @ConditionalOnClass 守卫）</li>
 * </ul>
 *
 * <p>注意：OtelAutoConfiguration / OtelSdkFactory 属于 agentcore-ext OTel middleware，
 * 其类随 ext 新版本发布后纳入 classpath；当前已发布 ext:0.1.0 尚未包含此类。
 * 验证 OtlpGrpcSpanExporter + OtelTracerSetup 即可覆盖核心依赖链。</p>
 */
class OtlpDependencyLoadTest {

    @Test
    @DisplayName("opentelemetry-exporter-otlp: OtlpGrpcSpanExporter 可加载")
    void otlpGrpcSpanExporter_loadable() {
        assertDoesNotThrow(
                () -> Class.forName("io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter"),
                "opentelemetry-exporter-otlp JAR 未在 classpath："
                        + "otel.enabled=true 时 OtelTracerSetup.createOtlpExporter() 将触发 NoClassDefFoundError");
    }

    @Test
    @DisplayName("opentelemetry-exporter-otlp: OtlpHttpSpanExporter 可加载")
    void otlpHttpSpanExporter_loadable() {
        assertDoesNotThrow(
                () -> Class.forName("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter"),
                "opentelemetry-exporter-otlp JAR 未在 classpath：HTTP 协议 OTLP 导出器不可用");
    }

    @Test
    @DisplayName("opentelemetry-exporter-sender-okhttp: OkHttpGrpcSender 可加载（otlp 1.55.0 传递 sender）")
    void otlpSenderOkhttp_loadable() {
        assertDoesNotThrow(
                () -> Class.forName("io.opentelemetry.exporter.sender.okhttp.internal.OkHttpGrpcSender"),
                "opentelemetry-exporter-sender-okhttp JAR 未在 classpath："
                        + "gRPC/HTTP 协议 OTLP sender 不可用");
    }

    @Test
    @DisplayName("agent-core-java tracerotel: OtelTracerSetup 可加载（@ConditionalOnClass 守卫类）")
    void otelTracerSetup_loadable() {
        Class<?> clazz = assertDoesNotThrow(
                () -> Class.forName("com.openjiuwen.extensions.tracerotel.OtelTracerSetup"),
                "agent-core-java tracerotel 扩展未在 classpath："
                        + "OtelAutoConfiguration 的 @ConditionalOnClass 将不满足，OTel 自动降级");
        assertNotNull(clazz);
    }
}
