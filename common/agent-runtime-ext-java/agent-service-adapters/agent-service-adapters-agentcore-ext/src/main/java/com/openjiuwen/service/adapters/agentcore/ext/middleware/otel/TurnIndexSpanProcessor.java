/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Writes {@code cascade.turn_index}（多轮轮次序号）onto chain spans at start time. The
 * round index comes from the batch-2 {@link TraceContextCarrier} 的 currentRunId
 * （{@code {taskId}#{roundSeq}} 的 roundSeq 段），由执行树装饰器开轮时回填。
 *
 * <p>carrier 为 Supplier 惰性解析：trajectory.link 未启用（或 V-8 关闭）时供应商返回
 * null，本处理器退化为 no-op——OTel 与 trajectory 两批可独立启停。
 *
 * @since 2026-08-26
 */
public final class TurnIndexSpanProcessor implements SpanProcessor {
    private static final String CHAIN_PREFIX = "chain.";

    private final Supplier<TraceContextCarrier> carrierSupplier;

    /**
     * Creates the processor.
     *
     * @param carrierSupplier lazy carrier supplier (may yield null when trajectory is off)
     */
    public TurnIndexSpanProcessor(Supplier<TraceContextCarrier> carrierSupplier) {
        this.carrierSupplier = carrierSupplier;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (!span.getName().startsWith(CHAIN_PREFIX)) {
            return;
        }
        Session session = SessionContextHolder.getCurrentSession();
        TraceContextCarrier carrier = carrierSupplier.get();
        if (session == null || session.getSessionId() == null || carrier == null) {
            return;
        }
        Optional<String> runId = carrier.find(session.getSessionId())
                .flatMap(TraceContextCarrier.Entry::getCurrentRunId);
        runId.ifPresent(id -> {
            java.util.OptionalLong seq = roundSeqOf(id);
            if (seq.isPresent()) {
                span.setAttribute("cascade.turn_index", seq.getAsLong());
            }
        });
    }

    private static java.util.OptionalLong roundSeqOf(String runId) {
        int hash = runId.lastIndexOf('#');
        if (hash < 0) {
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(Long.parseLong(runId.substring(hash + 1)));
        } catch (NumberFormatException e) {
            return java.util.OptionalLong.empty();
        }
    }

    @Override
    public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
        // turn index is written entirely at start time; nothing to do on end.
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
