/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class IntentPromptRailTest {
    @Test
    void restoresIntentToolAndAddsOneRequestSystemMessage() {
        ToolCard card = ToolCard.builder().id("intent_match").name("intent_match").description("route")
                .inputParams(Map.of()).build();
        IntentPromptRail rail = new IntentPromptRail(card, "route every request");
        ToolInfo other = ToolInfo.builder().name("other").description("other").parameters(Map.of()).build();
        List<Object> originalMessages = new ArrayList<>(List.of(new UserMessage("hello")));
        ModelCallInputs inputs = ModelCallInputs.builder().tools(new ArrayList<>(List.of(other)))
                .messages(originalMessages).build();
        AgentCallbackContext callback = AgentCallbackContext.builder().inputs(inputs).build();

        rail.beforeModelCall(callback);
        rail.beforeModelCall(callback);

        assertThat(inputs.getTools()).extracting(ToolInfo::getName).containsExactly("other", "intent_match");
        assertThat(inputs.getMessages()).hasSize(2);
        assertThat(inputs.getMessages().stream().filter(BaseMessage.class::isInstance).map(BaseMessage.class::cast)
                .filter(message -> IntentPromptRail.PROMPT_NAME.equals(message.getName()))).hasSize(1);
        assertThat(originalMessages).hasSize(1);
        SystemMessage prompt = (SystemMessage) inputs.getMessages().get(1);
        assertThat(prompt.getContentAsString()).isEqualTo("route every request");
    }

    @Test
    void preservesExistingIntentToolAndNamedPrompt() {
        ToolCard card = ToolCard.builder().id("intent_match").name("intent_match").description("route")
                .inputParams(Map.of()).build();
        IntentPromptRail rail = new IntentPromptRail(card, "new prompt");
        ToolInfo existing = ToolInfo.builder().name("intent_match").description("existing").parameters(Map.of())
                .build();
        SystemMessage existingPrompt = new SystemMessage("existing prompt", IntentPromptRail.PROMPT_NAME);
        ModelCallInputs inputs = ModelCallInputs.builder().tools(new ArrayList<>(List.of(existing)))
                .messages(new ArrayList<>(List.of(existingPrompt))).build();

        rail.beforeModelCall(AgentCallbackContext.builder().inputs(inputs).build());

        assertThat(inputs.getTools()).hasSize(1);
        assertThat(inputs.getTools().get(0).getDescription()).isEqualTo("existing");
        assertThat(inputs.getMessages()).containsExactly(existingPrompt);
    }
}
