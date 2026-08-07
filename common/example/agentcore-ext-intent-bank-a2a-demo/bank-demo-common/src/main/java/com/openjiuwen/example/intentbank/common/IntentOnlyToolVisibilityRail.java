/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.List;
import java.util.Set;

/** Hides local target tools from the model while retaining their executable registrations. */
public final class IntentOnlyToolVisibilityRail extends AgentRail {
    private static final int PRIORITY = 20;
    private static final Set<String> HIDDEN = Set.of(BankTools.CALCULATOR, BankTools.CURRENT_DATE, BankTools.WEATHER);

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void beforeModelCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ModelCallInputs inputs) || inputs.getTools() == null) {
            return;
        }
        List<ToolInfo> visible = inputs.getTools().stream()
                .filter(tool -> tool != null && !HIDDEN.contains(tool.getName())).toList();
        inputs.setTools(visible);
    }
}
