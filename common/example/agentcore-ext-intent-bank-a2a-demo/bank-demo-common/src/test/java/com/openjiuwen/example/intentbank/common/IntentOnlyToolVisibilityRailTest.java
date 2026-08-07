/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.Test;

import java.util.List;

class IntentOnlyToolVisibilityRailTest {
    @Test
    void hidesLocalTargetsButKeepsOtherModelTools() {
        ToolInfo calculator = ToolInfo.builder().name(BankTools.CALCULATOR).build();
        ToolInfo date = ToolInfo.builder().name(BankTools.CURRENT_DATE).build();
        ToolInfo weather = ToolInfo.builder().name(BankTools.WEATHER).build();
        ToolInfo planning = ToolInfo.builder().name("write_todos").build();
        ModelCallInputs inputs = ModelCallInputs.builder().tools(List.of(calculator, date, weather, planning)).build();

        new IntentOnlyToolVisibilityRail().beforeModelCall(AgentCallbackContext.builder().inputs(inputs).build());

        assertThat(inputs.getTools()).extracting(ToolInfo::getName).containsExactly("write_todos");
    }
}
