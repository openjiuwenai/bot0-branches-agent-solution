/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.react;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.ext.intent.tool.IntentRecognitionTool;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "openjiuwen.demo.intent.llm.provider=OpenAI", "openjiuwen.demo.intent.llm.api-key=test-chat-key",
        "openjiuwen.demo.intent.llm.api-base=https://chat.example/v1",
        "openjiuwen.demo.intent.llm.model-name=test-chat-model",
        "openjiuwen.demo.intent.reranker.api-key=test-reranker-key",
        "openjiuwen.demo.intent.reranker.api-base=https://reranker.example/v1",
        "openjiuwen.demo.intent.reranker.model-name=test-reranker-model" })
class ReactIntentRuntimeApplicationTest {
    @Autowired
    private AgentHandler handler;

    @Autowired
    private ReActAgent agent;

    @Autowired
    private IntentRecognitionTool<?> tool;

    @Test
    void mountsIntentToolOnReactAgentRuntime() {
        assertThat(handler).isInstanceOf(JiuwenCoreAgentExtHandler.class);
        assertThat(agent.getAbilityManager().get(tool.getCard().getName())).isSameAs(tool.getCard());
        assertThat(Runner.resourceMgr().getTool(tool.getCard().getId())).isSameAs(tool);
        assertThat(tool.getCard().getName()).isEqualTo("intent_recognition");
    }
}
