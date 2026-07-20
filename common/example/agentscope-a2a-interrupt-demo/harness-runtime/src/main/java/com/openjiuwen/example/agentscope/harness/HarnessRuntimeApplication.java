/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentscope.harness;

import com.openjiuwen.service.adapters.agentscope.agentfw.AgentScopeAgentHandler;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

/** HarnessAgent runtime used to verify A2A HITL interrupt and resume. */
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
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TransferTools());
        return HarnessAgent.builder()
            .name("runtime-harness-agent")
            .sysPrompt("""
                You are a transfer execution agent. When the user requests a transfer, you MUST call
                execute_transfer exactly once with the requested recipient and amount. Never claim that
                a transfer succeeded before the tool returns. After the tool returns, answer concisely.
                """)
            .model(deepSeekModel)
            .toolkit(toolkit)
            .permissionContext(askBeforeTransfer())
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

    private static PermissionContextState askBeforeTransfer() {
        return PermissionContextState.builder()
            .mode(PermissionMode.DEFAULT)
            .addAskRule("execute_transfer", new PermissionRule(
                "execute_transfer", null, PermissionBehavior.ASK, "demo-policy"))
            .build();
    }

    private static String required(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " must be configured");
        }
        return value;
    }

    static final class TransferTools {
        @Tool(name = "execute_transfer", description = "Execute a money transfer after user approval")
        public String executeTransfer(
            @ToolParam(name = "recipient", description = "Transfer recipient") String recipient,
            @ToolParam(name = "amount", description = "Amount in CNY") double amount) {
            return "Transfer executed: CNY " + amount + " to " + recipient;
        }
    }
}
