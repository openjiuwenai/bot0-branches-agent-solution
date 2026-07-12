/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.workflow;

import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.ext.intent.workflow.IntentRecognitionComponent;
import com.openjiuwen.example.intent.support.IntentDemoContext;
import com.openjiuwen.example.intent.support.IntentDemoProperties;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** A2A runtime exposing a Workflow Agent containing the intent recognition node. */
@SpringBootApplication
@EnableConfigurationProperties(IntentDemoProperties.class)
public class WorkflowIntentRuntimeApplication {
    static final String AGENT_ID = "intent-recognition-workflow-agent";
    static final String WORKFLOW_ID = "intent-recognition-workflow";

    public static void main(String[] args) {
        SpringApplication.run(WorkflowIntentRuntimeApplication.class, args);
    }

    @Bean
    IntentDemoContext intentDemoContext(IntentDemoProperties properties) {
        return IntentDemoContext.create(properties);
    }

    @Bean
    Workflow intentRecognitionWorkflow(IntentDemoContext context) {
        WorkflowCard card = WorkflowCard.builder().id(WORKFLOW_ID).name("IntentRecognitionWorkflow").version("1.0")
                .description("Matches a user request to the best configured standard A2A AgentCard")
                .inputParams(inputSchema()).build();
        Workflow workflow = new Workflow(card);
        workflow.setStartComp("start", new Start(), Map.of("utterance", "${query}"), null);
        workflow.addWorkflowComp("intent", new IntentRecognitionComponent<>(context.recognizer(), context.encoder()),
                Map.of("utterance", "${start.utterance}"), null);
        workflow.setEndComp("end", new End(),
                Map.of("matched", "${intent.matched}", "target", "${intent.target}", "reason", "${intent.reason}"),
                null);
        workflow.addConnection("start", "intent");
        workflow.addConnection("intent", "end");
        return workflow;
    }

    @Bean
    WorkflowAgent workflowAgent(IntentDemoProperties properties, Workflow workflow) {
        properties.requireConfigured();
        WorkflowAgentConfig config = WorkflowAgentConfig.builder().id(AGENT_ID)
                .description("Executes the intent recognition workflow for every user request")
                .model(properties.toModelConfig())
                .promptTemplate(List.of(Map.of("role", "system", "content", properties.getLlm().getSystemPrompt())))
                .build();
        WorkflowAgent agent = new WorkflowAgent(config);
        agent.addWorkflows(List.of(workflow));
        return agent;
    }

    @Bean
    AgentHandler workflowAgentHandler(WorkflowAgent agent) {
        return new JiuwenCoreAgentExtHandler(agent);
    }

    private static Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties",
                Map.of("query", Map.of("type", "string", "description", "Original user request")), "required",
                List.of("query"));
    }
}
