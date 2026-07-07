/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.rail;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Beta cognitive rail — secondary success-criteria gate on the final PEV output.
 *
 * <p>Hooks {@code afterInvoke} (terminal phase): reads the assembled output that
 * {@link com.openjiuwen.agents.pev.agent.PEVAgent} fires in the context payload, checks
 * every success-criterion keyword is present, records the verdict into observable fields.
 *
 * <p>This is <b>defense-in-depth</b> on top of PEV's internal verify (which drives dispatch):
 * a composable, independently-injected criteria check that other agent-service-app
 * implementations (e.g. a future EDPA) can reuse unchanged.
 
  * @since 2026-07*/
public class CriteriaVerificationRail extends AgentRail {

    private final Set<String> successCriteria;

    private boolean lastVerified = false;
    private Set<String> lastUnmet = new LinkedHashSet<>();

    public CriteriaVerificationRail(Set<String> successCriteria) {
        this.successCriteria = Set.copyOf(successCriteria);
    }

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        Object payload = (ctx.getExtra() == null) ? null : ctx.getExtra().get("payload");
        String output = (payload == null) ? "" : String.valueOf(payload);
        String lower = output.toLowerCase(Locale.ROOT);

        Set<String> unmet = new LinkedHashSet<>();
        for (String criterion : successCriteria) {
            if (!lower.contains(criterion.toLowerCase(Locale.ROOT))) {
                unmet.add(criterion);
            }
        }
        this.lastUnmet = unmet;
        this.lastVerified = unmet.isEmpty();
    }

    /** Whether the last observed output met all criteria. */
    public boolean lastVerified() {
        return lastVerified;
    }

    /** Criteria not found in the last output (empty when verified). */
    public Set<String> lastUnmet() {
        return lastUnmet;
    }
}