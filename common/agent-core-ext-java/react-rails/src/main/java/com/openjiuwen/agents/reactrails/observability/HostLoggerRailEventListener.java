/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import com.openjiuwen.core.common.logging.Loggers;
import com.openjiuwen.core.common.logging.LoggerProtocol;

/**
 * Default {@link RailEventListener} that routes each {@link RailEvent} to the host
 * {@link Loggers#AGENT} logger with a level matched to the transition's severity:
 * verified/steer/phase-override/compress = info, verify/replan-count = debug,
 * degrade = warning, device-failure fired = error.
 *
 * <p>Uses the agent-core-java host logger so react-rails adds zero new logging dependencies
 * (no slf4j/log4j/jul binding to ship) and inherits the host's trace-id correlation.
 *
 * @since 2026-07
 */
public class HostLoggerRailEventListener implements RailEventListener {
    private static final LoggerProtocol LOG = Loggers.AGENT;

    /**
     * Routes one event to the host agent logger.
     *
     * @param event the rail state-transition event
     */
    @Override
    public void onRailEvent(RailEvent event) {
        String rail = event.railName();
        if (event instanceof RailEvent.ForceFinishEvent fe) {
            if (fe.verified()) {
                LOG.info("[{}] Exit-1 verified: {}", rail, fe.details());
            } else {
                LOG.warning("[{}] Exit-3 degraded: {}", rail, fe.details());
            }
        } else if (event instanceof RailEvent.SteeringEvent se) {
            LOG.info("[{}] Exit-2 steering (source={}): {}", rail, se.source(), se.hintExcerpt());
        } else if (event instanceof RailEvent.VerifyEvent ve) {
            LOG.debug("[{}] verify verdict (passed={}, violations={})",
                    rail, ve.passed(), ve.violationCount());
        } else if (event instanceof RailEvent.ReplanCountEvent re) {
            LOG.debug("[{}] replan count++ -> {} (source={}, max={})",
                    rail, re.count(), re.source(), re.maxReplan());
        } else if (event instanceof RailEvent.PhaseOverrideEvent pe) {
            LOG.info("[{}] phase override (mode={}): {}", rail, pe.mode(), pe.overrideExcerpt());
        } else if (event instanceof RailEvent.ContextCompressedEvent ce) {
            LOG.info("[{}] context compressed: {} -> {} messages",
                    rail, ce.beforeMsgCount(), ce.afterMsgCount());
        } else if (event instanceof RailEvent.DeviceFailureEvent de) {
            if ("FIRED".equals(de.phase())) {
                LOG.error("[{}] device-failure degrade fired (tool={})", rail, de.tool());
            } else {
                LOG.warning("[{}] device-failure marked (tool={})", rail, de.tool());
            }
        } else {
            // RailEvent is sealed; future variants land here instead of being silently dropped.
            LOG.debug("[{}] unmapped rail event: {}", rail, event.type());
        }
    }
}
