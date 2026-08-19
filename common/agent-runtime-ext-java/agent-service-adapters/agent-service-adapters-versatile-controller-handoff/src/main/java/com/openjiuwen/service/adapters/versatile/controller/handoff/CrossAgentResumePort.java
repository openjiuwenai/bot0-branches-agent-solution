/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

/**
 * Module-internal port (NOT a public A2A SPI) for a future cross-agent resume
 * feature to persist target-agent / remote-task / resume-route associations.
 * No default implementation bean; while absent, downstream INPUT_REQUIRED always
 * fails with VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED (spec 4.5.1).
 *
 * @since 2026-08-19
 */
public interface CrossAgentResumePort {

    /**
     * Saves the cross-agent resume association.
     *
     * @return true only when the association was persisted; the executor surfaces a
     *         resumable TYPE_INTERRUPT exclusively on true.
     */
    boolean saveResumeAssociation(String conversationId, String targetAgentId,
            String remoteTaskId, String resumeRouteHint);
}
