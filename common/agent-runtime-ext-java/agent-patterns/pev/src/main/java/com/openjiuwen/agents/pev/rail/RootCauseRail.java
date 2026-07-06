/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.rail;

import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beta cognitive rail — device-failure observability across PEV execution supersteps.
 *
 * <p>Hooks {@code afterToolCall} (per execute superstep): inspects the node-result map
 * that {@link com.openjiuwen.agents.pev.agent.PEVAgent} fires, accumulates
 * {@link NodeResult.DeviceFailure} occurrences. Provides an independent diagnostic
 * summary for observability — separate from PEV's internal diagnose (which drives
 * dispatch), this rail records device-failure telemetry across the whole run.
 *
 * <p>Composable: register via {@link com.openjiuwen.core.singleagent.BaseAgent#registerRail}.
 */
public class RootCauseRail extends AgentRail {

    private final AtomicInteger deviceFailureCount = new AtomicInteger();
    private final Set<String> deviceFailedNodes = new LinkedHashSet<>();

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        Object payload = (ctx.getExtra() == null) ? null : ctx.getExtra().get("payload");
        if (payload instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof NodeResult.DeviceFailure df) {
                    deviceFailureCount.incrementAndGet();
                    if (df.nodeId() != null) {
                        deviceFailedNodes.add(df.nodeId());
                    }
                }
            }
        }
    }

    /** Total DeviceFailure occurrences observed across all supersteps. */
    public int deviceFailureCount() {
        return deviceFailureCount.get();
    }

    /** Distinct node IDs that produced a DeviceFailure. */
    public Set<String> deviceFailedNodes() {
        return Set.copyOf(deviceFailedNodes);
    }
}