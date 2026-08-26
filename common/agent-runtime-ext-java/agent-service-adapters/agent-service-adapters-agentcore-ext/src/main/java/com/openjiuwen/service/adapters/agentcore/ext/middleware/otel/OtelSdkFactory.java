/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/**
 * Builds the {@link SdkTracerProvider} used by the trajectory pipeline. The provider is
 * self-built (instead of reusing {@code OtelTracerSetup}) so the Resource can carry
 * {@code service.instance.id}, which the extension's setup does not support.
 *
 * @since 2026-08-07
 */
public final class OtelSdkFactory {
    private OtelSdkFactory() {
    }

    /**
     * Creates a provider wired as: SessionIdSpanProcessor → BatchSpanProcessor →
     * OtelCompatSpanExporter → the given exporter.
     *
     * @param config     tracer configuration (from {@link OtelEnvProperties#toTracerConfig()})
     * @param exporter   downstream exporter (OTLP in production, in-memory for tests)
     * @param instanceId value for {@code service.instance.id}; a random UUID is used when null/blank
     * @return configured provider; caller owns shutdown (forceFlush then close)
     */
    public static SdkTracerProvider createProvider(OtelTracerConfig config, SpanExporter exporter,
                                                   String instanceId) {
        return createProvider(config, exporter, instanceId, () -> null);
    }

    /**
     * Creates a provider additionally wiring {@link TurnIndexSpanProcessor}（cascade.turn_index）
     * with a lazy carrier supplier.
     *
     * @param config          tracer configuration
     * @param exporter        downstream exporter
     * @param instanceId      service.instance.id value
     * @param carrierSupplier lazy trace-context-carrier supplier (turn index source)
     * @return configured provider; caller owns shutdown
     */
    public static SdkTracerProvider createProvider(OtelTracerConfig config, SpanExporter exporter,
                                                   String instanceId,
                                                   Supplier<TraceContextCarrier> carrierSupplier) {
        String resolvedInstanceId = instanceId == null || instanceId.isBlank()
                ? UUID.randomUUID().toString() : instanceId;
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), config.getServiceName(),
                AttributeKey.stringKey("service.version"),
                config.getServiceVersion() != null ? config.getServiceVersion() : "unknown",
                AttributeKey.stringKey("service.instance.id"), resolvedInstanceId)));

        SpanExporter compatExporter = new OtelCompatSpanExporter(exporter);
        BatchSpanProcessor batchProcessor = BatchSpanProcessor.builder(compatExporter)
                .setScheduleDelay(config.getScheduleDelayMillis(), TimeUnit.MILLISECONDS)
                .setMaxExportBatchSize(config.getMaxExportBatchSize())
                .setExporterTimeout(config.getExportTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();

        return SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(config.getSampleRate())))
                .addSpanProcessor(new SessionIdSpanProcessor())
                .addSpanProcessor(new TurnIndexSpanProcessor(carrierSupplier))
                .addSpanProcessor(batchProcessor)
                .build();
    }
}
