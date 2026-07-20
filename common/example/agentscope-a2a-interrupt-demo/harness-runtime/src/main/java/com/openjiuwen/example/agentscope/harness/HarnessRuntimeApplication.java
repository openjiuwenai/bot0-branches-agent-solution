/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentscope.harness;

import com.openjiuwen.service.adapters.agentscope.agentfw.AgentScopeAgentHandler;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * HarnessAgent runtime used to verify A2A external-tool interrupt and resume.
 *
 * @since 2026-07-20
 */
@SpringBootApplication
public class HarnessRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(HarnessRuntimeApplication.class, args);
    }

    @Bean
    Model deepSeekModel(
        @Value("${agentscope.demo.api-key}") String apiKey,
        @Value("${agentscope.demo.base-url}") String baseUrl,
        @Value("${agentscope.demo.model}") String modelName) {
        return OpenAIChatModel.builder()
            .apiKey(required(apiKey, "DEEPSEEK_API_KEY"))
            .baseUrl(required(baseUrl, "DEEPSEEK_BASE_URL"))
            .modelName(required(modelName, "DEEPSEEK_MODEL"))
            .stream(true)
            .build();
    }

    @Bean
    HarnessAgent harnessAgent(
        Model deepSeekModel,
        @Value("${agentscope.demo.workspace}") String workspace) {
        return HarnessAgent.builder()
            .name("runtime-harness-agent")
            .sysPrompt("""
                You are an external information lookup agent. For every user request, you MUST call
                external_lookup exactly once. Extract the customer identifier into customer_id and the
                requested information into attribute. The tool is executed outside AgentScope. After its
                result is supplied, summarize that result concisely.
                """)
            .model(deepSeekModel)
            .toolkit(externalToolkit())
            .workspace(Path.of(workspace))
            .disableFilesystemTools()
            .disableShellTool()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableSubagents()
            .disableDynamicSkills()
            .disableCompaction()
            .disableToolResultEviction()
            .build();
    }

    @Bean
    AgentScopeAgentHandler agentHandler(HarnessAgent harnessAgent) {
        return AgentScopeAgentHandler.forHarnessAgent(harnessAgent);
    }

    static Toolkit externalToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(ToolSchema.builder()
            .name("external_lookup")
            .description("Look up information in an external system")
            .parameters(Map.of(
                "type", "object",
                "properties", Map.of(
                    "customer_id", Map.of("type", "string", "description", "Customer identifier"),
                    "attribute", Map.of(
                        "type", "string",
                        "description", "Customer attribute to retrieve, for example account_tier")),
                "required", List.of("customer_id", "attribute"),
                "additionalProperties", false))
            .build());
        return toolkit;
    }

    private static String required(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " must be configured");
        }
        return value;
    }
}
