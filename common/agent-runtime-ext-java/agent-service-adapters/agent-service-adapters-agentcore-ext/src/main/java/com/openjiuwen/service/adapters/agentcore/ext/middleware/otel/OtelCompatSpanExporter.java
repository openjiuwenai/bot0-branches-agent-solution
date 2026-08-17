/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * This file subclasses io.opentelemetry.sdk.trace.data.DelegatingSpanData from the
 * OpenTelemetry Java SDK (Apache License 2.0, https://opentelemetry.io/) — usage by
 * extension, no source copied.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SpanExporter wrapper that dual-writes contract-required attribute aliases before
 * forwarding: new GenAI token names alongside the extension's legacy names, the business
 * finish_reason attribute, and timezone-corrected openjiuwen time attributes. SpanData is
 * immutable, so attributes are rebuilt via {@link DelegatingSpanData}.
 *
 * @since 2026-08-07
 */
public final class OtelCompatSpanExporter implements SpanExporter {

    private static final AttributeKey<Long> OLD_INPUT = AttributeKey.longKey("gen_ai.usage.prompt_tokens");
    private static final AttributeKey<Long> OLD_OUTPUT = AttributeKey.longKey("gen_ai.usage.completion_tokens");
    private static final AttributeKey<Long> NEW_INPUT = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> NEW_OUTPUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> GENAI_FINISH = AttributeKey.stringKey("gen_ai.response.finish_reason");
    private static final AttributeKey<String> BIZ_FINISH = AttributeKey.stringKey("openjiuwen.llm.finish_reason");
    private static final List<AttributeKey<String>> TIME_KEYS = List.of(
            AttributeKey.stringKey("openjiuwen.start_time"),
            AttributeKey.stringKey("openjiuwen.end_time"));

    private final SpanExporter delegate;

    public OtelCompatSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        // 每个 span 包装为契约兼容视图后委托导出（stream 管道，避免逐条显式拷贝）
        return delegate.export(spans.stream().map(CompatSpanData::new).collect(Collectors.toList()));
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private static Attributes compatAttributes(Attributes source) {
        AttributesBuilder builder = source.toBuilder();
        copyIfAbsent(source, builder, OLD_INPUT, NEW_INPUT);
        copyIfAbsent(source, builder, OLD_OUTPUT, NEW_OUTPUT);
        copyIfAbsent(source, builder, GENAI_FINISH, BIZ_FINISH);
        for (AttributeKey<String> key : TIME_KEYS) {
            String value = source.get(key);
            if (value == null) {
                continue;
            }
            Optional<String> zoned = toZonedIso(value);
            if (zoned.isEmpty()) {
                builder.remove(key);
            } else {
                builder.put(key, zoned.get());
            }
        }
        return builder.build();
    }

    private static <T> void copyIfAbsent(Attributes source, AttributesBuilder builder,
                                         AttributeKey<T> from, AttributeKey<T> to) {
        T value = source.get(from);
        if (value != null && source.get(to) == null) {
            builder.put(to, value);
        }
    }

    private static Optional<String> toZonedIso(String value) {
        if (value.matches(".*([+-]\\d{2}:\\d{2}|Z)$")) {
            return Optional.of(value);
        }
        try {
            // 引擎侧以 LocalDateTime.now() 产出时间（OtelAgentHandler/OtelWorkflowHandler），
            // 即系统默认时区的本地时间；本 exporter 与引擎同 JVM 运行，用 systemDefault 还原
            // 时区语义正确——跨时区部署时各 Agent 各自产出正确的绝对时间。切勿改为固定 UTC。
            return Optional.of(LocalDateTime.parse(value).atZone(ZoneId.systemDefault())
                    .toOffsetDateTime().toString());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static final class CompatSpanData extends DelegatingSpanData {
        private final Attributes attributes;

        private CompatSpanData(SpanData delegate) {
            super(delegate);
            this.attributes = compatAttributes(delegate.getAttributes());
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }
    }
}
