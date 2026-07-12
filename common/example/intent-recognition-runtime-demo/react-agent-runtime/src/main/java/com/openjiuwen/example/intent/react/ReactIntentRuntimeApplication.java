/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.react;

import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.ext.intent.tool.IntentRecognitionTool;
import com.openjiuwen.ext.intent.tool.IntentRecognitionToolConfig;
import com.openjiuwen.example.intent.support.IntentDemoContext;
import com.openjiuwen.example.intent.support.IntentDemoProperties;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** A2A runtime exposing a ReAct Agent with the intent recognition Tool. */
@SpringBootApplication
@EnableConfigurationProperties(IntentDemoProperties.class)
public class ReactIntentRuntimeApplication {
    static final String AGENT_ID = "intent-recognition-react-agent";
    static final String TOOL_ID = "intent-recognition-react-tool";

    public static void main(String[] args) {
        SpringApplication.run(ReactIntentRuntimeApplication.class, args);
    }

    @Bean
    IntentDemoContext intentDemoContext(IntentDemoProperties properties) {
        return IntentDemoContext.create(properties);
    }

    @Bean
    IntentRecognitionTool<AgentCard> intentRecognitionTool(IntentDemoContext context) {
        return new IntentRecognitionTool<>(context.recognizer(), context.encoder(),
                new IntentRecognitionToolConfig(TOOL_ID, "intent_recognition"));
    }

    @Bean
    ReActAgent reactAgent(IntentDemoProperties properties, IntentRecognitionTool<AgentCard> tool) {
        properties.requireConfigured();
        ReActAgent agent = new ReActAgent(com.openjiuwen.core.singleagent.schema.AgentCard.builder().id(AGENT_ID)
                .name("IntentRecognitionReActAgent").description("Routes requests to standard A2A AgentCards").build());
        IntentDemoProperties.Llm llm = properties.getLlm();
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", llm.getSystemPrompt())))
                .maxIterations(llm.getMaxIterations()).build().configureModelClient(llm.getProvider(), llm.getApiKey(),
                        llm.getApiBase(), llm.getModelName(), llm.isSslVerify());
        ModelRequestConfig requestConfig = config.getModelConfigObj();
        requestConfig.setTemperature(llm.getTemperature());
        requestConfig.setTopP(llm.getTopP());
        requestConfig.setMaxTokens(llm.getMaxTokens());
        agent.configure(config);

        Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
        Runner.resourceMgr().addTool(tool, AGENT_ID);
        agent.getAbilityManager().add(tool.getCard());
        return agent;
    }

    @Bean
    AgentHandler reactAgentHandler(ReActAgent agent) {
        return new JiuwenCoreAgentExtHandler(agent);
    }

    @Bean
    DisposableBean intentToolCleanup() {
        return () -> Runner.resourceMgr().removeTool(TOOL_ID, AGENT_ID, TagMatchStrategy.ALL, true);
    }
}
