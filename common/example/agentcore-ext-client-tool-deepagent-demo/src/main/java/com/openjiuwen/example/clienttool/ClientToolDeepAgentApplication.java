/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.clienttool;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime hosting a DeepAgent that can call request-scoped client tools.
 *
 * @since 2026-07-24
 */
@SpringBootApplication
public class ClientToolDeepAgentApplication {
    private static final String SYSTEM_PROMPT = """
            你是端侧工具联调 Agent。严格遵循以下规则：
            1. 用户消息包含 SINGLE_CLIENT_TOOL_DEMO 时，必须调用 getLocalWeather，city 固定为 深圳；
               在拿到工具结果前禁止直接回答。
            2. 用户消息包含 MULTI_CLIENT_TOOL_DEMO 时，必须在同一个 assistant turn 同时调用两个工具：
               getLocalWeather(city=深圳)；createCalendarEvent(title=客户端工具联调,
               date=2026-07-25,durationMinutes=30)。在两个工具结果都返回前禁止直接回答。
            3. 工具结果返回后，不要再次调用工具；最终回答必须逐字包含每个客户端结果中的
               toolName、receivedArguments 和 result，便于验证真实模型看到的调用参数和客户端回灌结果。
            """;

    public static void main(String[] args) {
        SpringApplication.run(ClientToolDeepAgentApplication.class, args);
    }

    @Bean
    AgentHandler deepAgentHandler(
            @Value("${openjiuwen.demo.llm.api-key:}") String apiKey,
            @Value("${openjiuwen.demo.llm.api-base:https://api.deepseek.com}") String apiBase,
            @Value("${openjiuwen.demo.llm.provider:OpenAI}") String provider,
            @Value("${openjiuwen.demo.llm.model-name:deepseek-chat}") String modelName,
            @Value("${openjiuwen.demo.llm.workspace-path:target/client-tool-workspace}") String workspacePath) {
        requireText(apiKey, "openjiuwen.demo.llm.api-key");
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", modelName);
        model.put("temperature", 0.0);
        model.put("top_p", 0.8);
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", provider);
        backend.put("api_key", apiKey);
        backend.put("api_base", apiBase);
        backend.put("verify_ssl", true);
        backend.put("timeout", 120);

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .maxIterations(8)
                .enableTaskLoop(true)
                .workspacePath(workspacePath)
                .model(model)
                .backend(backend)
                .build();
        AgentCard card = AgentCard.builder()
                .id("client-tool-deepagent")
                .name("ClientToolDeepAgent")
                .description("DeepAgent runtime for request-scoped client tool calls")
                .build();
        Workspace workspace = Workspace.builder().rootPath(workspacePath).language("zh-CN").build();
        return new JiuwenCoreAgentExtHandler(new DeepAgent(card, config, workspace));
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
    }
}
