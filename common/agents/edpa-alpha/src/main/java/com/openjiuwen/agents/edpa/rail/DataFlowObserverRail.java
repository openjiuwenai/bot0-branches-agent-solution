/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.EventInputs;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DataFlow 可观测 rail — trace span 架构（OpenTelemetry），配置自动降档。
 *
 * <p><b>诚实边界（4-lens 校正）</b>：本 rail 是 SPI 占位——需显式 registerRail 接到 agent
 * （EdpaAutoConfiguration 默认不注册）。payload 读取用 {@code ctx.getExtra().get("payload")}
 * 键（PEVAgent 约定写此键，ReActAgent/DeepAgent callback 不写——需宿主适配改键）。
 * trace span 架构就位（OTel spanBuilder + try/finally），接线后即可观测。
 *
 * <p><b>三级配置降档</b>（edpa.trace.level）：
 * <ul>
 *   <li><b>FULL</b> — OTel span（tracer.spanBuilder + setAttribute + end），export 到 Jaeger/Zipkin 看火焰图</li>
 *   <li><b>SIMPLE</b> — record 到 list（降档，无 OTel 依赖开销，本地 print/collect）</li>
 *   <li><b>OFF</b> — 不观测（零开销）</li>
 * </ul>
 *
 * <p>Hooks afterModelCall（span=llm.call）+ afterToolCall（span=tool.call）。每步数据流
 * （LLM response 摘要 / tool result 摘要）钉成 span/record，黑盒变白盒。
 *
 * @since 2026-07
 */
public class DataFlowObserverRail extends AgentRail {
    private final TraceLevel level;
    private final Tracer tracer;
    private final List<String> simpleObservations;

    /**
     * 构造（指定级别 + OTel tracer）。
     *
     * @param level FULL/SIMPLE/OFF
     * @param tracer OTel tracer（FULL 模式用，SIMPLE/OFF 传 null）
     */
    public DataFlowObserverRail(TraceLevel level, Tracer tracer) {
        this.level = level;
        this.tracer = (level == TraceLevel.FULL) ? tracer : null;
        this.simpleObservations = (level == TraceLevel.SIMPLE) ? new ArrayList<>() : null;
    }

    /**
     * FULL 模式（OTel span）。
     *
     * @param tracer OTel tracer（FULL 模式用）
     */
    public DataFlowObserverRail(Tracer tracer) {
        this(TraceLevel.FULL, tracer);
    }

    /**
     * SIMPLE 模式（record 降档，默认无 OTel）。
     *
     * @since 2026-07
     */
    public DataFlowObserverRail() {
        this(TraceLevel.SIMPLE, null);
    }

    /**
     * LLM 调用后 hook（span 名 {@code llm.call}）。
     *
     * @param ctx 回调上下文（含 payload/inputs）
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        observe("llm.call", ctx);
    }

    /**
     * Tool 调用后 hook（span 名 {@code tool.call}）。
     *
     * @param ctx 回调上下文（含 payload/inputs）
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        observe("tool.call", ctx);
    }

    /**
     * 按当前 trace 级别记录一次观测（FULL→span / SIMPLE→list / OFF→空操作）。
     *
     * @param spanName span 名（如 {@code llm.call}）
     * @param ctx 回调上下文（用于取 payload）
     */
    private void observe(String spanName, AgentCallbackContext ctx) {
        if (level == TraceLevel.OFF) {
            return;
        }
        Object payload = payloadOf(ctx);
        String summary = summarize(payload);
        if (level == TraceLevel.FULL) {
            recordSpan(spanName, payload, summary);
        } else if (level == TraceLevel.SIMPLE) {
            recordSimple(spanName, summary);
        } else {
            return; // OFF handled by early return above
        }
    }

    /**
     * FULL 模式：建一个 OTel span，写 summary/length 属性后 end（try/finally 保证 end）。
     *
     * @param spanName span 名
     * @param payload 原始 payload（用于取 length）
     * @param summary 已摘要化的 payload 文本
     */
    private void recordSpan(String spanName, Object payload, String summary) {
        if (tracer == null) {
            return;
        }
        int len = payload != null ? String.valueOf(payload).length() : 0;
        Span span = tracer.spanBuilder(spanName).startSpan();
        try {
            span.setAttribute("payload.summary", summary);
            span.setAttribute("payload.length", len);
        } finally {
            span.end();
        }
    }

    /**
     * SIMPLE 模式：把 {@code [spanName] summary} 追加到本地观测 list。
     *
     * @param spanName span 名
     * @param summary 已摘要化的 payload 文本
     */
    private void recordSimple(String spanName, String summary) {
        if (simpleObservations != null) {
            simpleObservations.add("[" + spanName + "] " + summary);
        }
    }

    /**
     * SIMPLE 模式的观测记录（FULL/OFF 返回空）。
     *
     * @return immutable copy of observations
     */
    public List<String> getObservations() {
        return simpleObservations != null ? List.copyOf(simpleObservations) : List.of();
    }

    /**
     * SIMPLE 模式的事件数（FULL/OFF 返回 0）。
     *
     * @return 已记录的观测数；非 SIMPLE 模式返回 {@code 0}
     */
    public int eventCount() {
        return simpleObservations != null ? simpleObservations.size() : 0;
    }

    /**
     * 当前 trace 级别。
     *
     * @return 构造时传入的 {@link TraceLevel}
     */
    public TraceLevel getLevel() {
        return level;
    }

    private static Object payloadOf(AgentCallbackContext ctx) {
        // 1. extra "payload" key (PEVAgent convention, backward compat)
        Object fromExtra = payloadFromExtra(ctx.getExtra()).orElse(null);
        if (fromExtra != null) {
            return fromExtra;
        }
        // 2. inputs (ReActAgent/DeepAgent real callback data — 4-lens fix)
        return payloadFromInputs(ctx.getInputs()).orElse(null);
    }

    private static Optional<Object> payloadFromExtra(Map<String, Object> extra) {
        if (extra == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(extra.get("payload"));
    }

    private static Optional<Object> payloadFromInputs(EventInputs inputs) {
        if (inputs instanceof ModelCallInputs modelInputs) {
            return Optional.ofNullable(modelInputs.getResponse());
        }
        if (inputs instanceof ToolCallInputs toolInputs) {
            return Optional.of(toolInputs.getToolName() + " → " + toolInputs.getToolResult());
        }
        return Optional.empty();
    }

    private static String summarize(Object payload) {
        String s = (payload == null) ? "(no payload)" : String.valueOf(payload);
        return (s.length() <= 100) ? s : s.substring(0, 100) + "...(" + s.length() + " chars)";
    }

    /**
     * Trace 级别（配置降档）。
     *
     * @since 2026-07
     */
    public enum TraceLevel {
        /** OTel span + export（Jaeger/Zipkin 火焰图）。 */
        FULL,
        /** record 到 list（降档，本地 collect）。 */
        SIMPLE,
        /** 不观测（零开销）。 */
        OFF
    }
}
