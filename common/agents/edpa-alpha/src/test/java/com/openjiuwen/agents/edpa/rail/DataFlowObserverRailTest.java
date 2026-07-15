/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * DataFlowObserverRail bearing tests — three trace levels (FULL/SIMPLE/OFF).
 *
 * @since 2026-07
 */
class DataFlowObserverRailTest {
    @Test
    void simpleModeRecordsModelAndToolEvents() {
        DataFlowObserverRail rail = new DataFlowObserverRail();
        assertThat(rail.getLevel()).isEqualTo(DataFlowObserverRail.TraceLevel.SIMPLE);
        rail.afterModelCall(ctxWithPayload("LLM response: 苹果营收 802 亿"));
        rail.afterToolCall(ctxWithPayload("tool result: {GDP=稳中有降}"));
        assertThat(rail.eventCount()).isEqualTo(2);
        assertThat(rail.getObservations().get(0)).contains("[llm.call]").contains("802");
        assertThat(rail.getObservations().get(1)).contains("[tool.call]").contains("GDP");
    }

    @Test
    void offModeObservesNothing() {
        DataFlowObserverRail rail = new DataFlowObserverRail(DataFlowObserverRail.TraceLevel.OFF, null);
        rail.afterModelCall(ctxWithPayload("should not record"));
        rail.afterToolCall(ctxWithPayload("should not record"));
        assertThat(rail.eventCount()).isZero();
        assertThat(rail.getObservations()).isEmpty();
    }

    @Test
    void fullModeWithTracerCreatesSpanNoException() {
        // FULL 模式需要 OTel tracer；用 noop tracer（GlobalOpenTelemetry noop）验证不抛异常
        io.opentelemetry.api.OpenTelemetry otel = io.opentelemetry.api.GlobalOpenTelemetry.get();
        DataFlowObserverRail rail = new DataFlowObserverRail(DataFlowObserverRail.TraceLevel.FULL,
                otel.getTracer("edpa-test"));
        rail.afterModelCall(ctxWithPayload("LLM response content"));
        rail.afterToolCall(ctxWithPayload("tool result"));
        // FULL 模式不 record 到 list（span 是 OTel 内部），eventCount=0
        assertThat(rail.eventCount()).isZero();
    }

    @Test
    void nullPayloadRecordedGracefully() {
        DataFlowObserverRail rail = new DataFlowObserverRail();
        rail.afterModelCall(ctxWithPayload(null));
        assertThat(rail.getObservations().get(0)).contains("(no payload)");
    }

    @Test
    void longPayloadTruncated() {
        DataFlowObserverRail rail = new DataFlowObserverRail();
        String longPayload = "这是一个非常非常长的数据流内容超过一百字符限制需要被截断处理以便观测摘要保持简洁可读不会占用太多显示空间".repeat(3);
        rail.afterToolCall(ctxWithPayload(longPayload));
        assertThat(rail.getObservations().get(0)).contains("...(").contains("chars");
    }

    private static AgentCallbackContext ctxWithPayload(Object payload) {
        return AgentCallbackContext.builder().extra(payload != null ? Map.of("payload", payload) : Map.of()).build();
    }
}
