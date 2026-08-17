/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

/**
 * OtelCompatSpanExporter 契约双写的单元测试。
 */
class OtelCompatSpanExporterTest {

    private static final AttributeKey<Long> OLD_INPUT = AttributeKey.longKey("gen_ai.usage.prompt_tokens");
    private static final AttributeKey<Long> OLD_OUTPUT = AttributeKey.longKey("gen_ai.usage.completion_tokens");
    private static final AttributeKey<Long> NEW_INPUT = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> NEW_OUTPUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> GENAI_FINISH = AttributeKey.stringKey("gen_ai.response.finish_reason");
    private static final AttributeKey<String> BIZ_FINISH = AttributeKey.stringKey("openjiuwen.llm.finish_reason");
    private static final AttributeKey<String> OJ_START = AttributeKey.stringKey("openjiuwen.start_time");

    private SpanData exportSingle(Attributes attributes) {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new OtelCompatSpanExporter(memory)))
                .build();
        provider.get("t").spanBuilder("s").setAllAttributes(attributes).startSpan().end();
        provider.forceFlush();
        var items = memory.getFinishedSpanItems();
        provider.close();
        return items.get(0);
    }

    @Test
    void export_addsNewTokenNamesFromOld() {
        SpanData span = exportSingle(Attributes.of(OLD_INPUT, 10L, OLD_OUTPUT, 20L));
        assertThat(span.getAttributes().get(NEW_INPUT)).isEqualTo(10L);
        assertThat(span.getAttributes().get(NEW_OUTPUT)).isEqualTo(20L);
        assertThat(span.getAttributes().get(OLD_INPUT)).isEqualTo(10L);
    }

    @Test
    void export_doesNotOverwriteExistingNewNames() {
        SpanData span = exportSingle(Attributes.of(OLD_INPUT, 10L, NEW_INPUT, 99L));
        assertThat(span.getAttributes().get(NEW_INPUT)).isEqualTo(99L);
    }

    @Test
    void export_addsBusinessFinishReason() {
        SpanData span = exportSingle(Attributes.of(GENAI_FINISH, "stop"));
        assertThat(span.getAttributes().get(BIZ_FINISH)).isEqualTo("stop");
    }

    @Test
    void export_rewritesNaiveTimeToZonedIso() {
        SpanData span = exportSingle(Attributes.of(OJ_START, "2026-07-22T14:50:24.910795"));
        String rewritten = span.getAttributes().get(OJ_START);
        assertThat(rewritten).startsWith("2026-07-22T14:50:24.910795").matches(".*[+-]\\d{2}:\\d{2}$");
    }

    @Test
    void export_unparseableTimeAttributeRemoved() {
        SpanData span = exportSingle(Attributes.of(OJ_START, "not-a-time"));
        assertThat(span.getAttributes().get(OJ_START)).isNull();
    }

    @Test
    void export_leavesOtherAttributesUntouched() {
        SpanData span = exportSingle(Attributes.of(AttributeKey.stringKey("k"), "v"));
        assertThat(span.getAttributes().get(AttributeKey.stringKey("k"))).isEqualTo("v");
    }

    @Test
    void flushAndShutdown_delegate() {
        InMemorySpanExporter memory = InMemorySpanExporter.create();
        OtelCompatSpanExporter exporter = new OtelCompatSpanExporter(memory);
        assertThat(exporter.flush().isSuccess()).isTrue();
        assertThat(exporter.shutdown().isSuccess()).isTrue();
    }
}
