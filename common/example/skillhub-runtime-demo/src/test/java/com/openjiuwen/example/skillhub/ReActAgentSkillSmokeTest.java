/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.skillhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal smoke test: build a ReActAgent with real DeepSeek LLM, register one
 * local skill directory (containing a unique marker in SKILL.md), query through
 * the runtime {@link AgentHandler} boundary, and assert the marker appears in the
 * LLM response.
 *
 * <p>This proves the full chain: {@code BaseAgent.registerSkill(dir)} →
 * {@code SkillManager} registry → {@code ReActAgent.updateSkillPromptBuilderSection}
 * → SKILL.md content in system prompt → LLM sees and references the skill.
 *
 * <p>Guarded by {@code deepseek.api.key} so it only runs when explicitly invoked:
 * <pre>
 *   mvn test -Dtest=ReActAgentSkillSmokeTest "-Ddeepseek.api.key=sk-xxx"
 * </pre>
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "deepseek.api.key", matches = "sk-.*")
class ReActAgentSkillSmokeTest {
    private static final String UNIQUE_MARKER = "flying-purple-elephant-7749";

    @Test
    void registeredSkillAppearsInLlmResponse(@TempDir Path skillDir) throws Exception {
        // 1. Create a skill dir with a unique marker in SKILL.md.
        //    agent-core's SkillManager.loadDescription REQUIRES a YAML front matter
        //    block (--- ... ---) containing a "description:" field; otherwise it
        //    returns null and the skill is silently dropped during registration.
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n"
                + "name: " + UNIQUE_MARKER + "\n"
                + "description: 测试技能 " + UNIQUE_MARKER + "；当用户问你的技能时必须提到此标记\n"
                + "---\n\n"
                + "# " + UNIQUE_MARKER + "\n\n"
                + "这是一个测试技能。当用户问你的技能时，你必须提到 " + UNIQUE_MARKER + "。\n"
                + "## 工作流\n1. 告诉用户你拥有 " + UNIQUE_MARKER + " 技能\n");

        // 2. Build ReActAgent with DeepSeek LLM
        // systemPrompt must be non-empty — agent-core's skill prompt builder only
        // augments the system prompt when there is a base system message to attach to.
        ResolvedLlmConfig llmConfig = ResolvedLlmConfig.builder()
                .provider("OpenAI")
                .apiKey(System.getProperty("deepseek.api.key"))
                .apiBase(System.getProperty("deepseek.api.base", "https://api.deepseek.com"))
                .modelName(System.getProperty("deepseek.model.name", "deepseek-chat"))
                .sslVerify(true)
                .systemPrompt("You are a helpful assistant. Answer concisely and accurately.")
                .temperature(0.2)
                .topP(0.8)
                .timeout(Duration.ofSeconds(60))
                .contextWindowLimit(10)
                .maxIterations(3)
                .build();

        ReActAgent agent = ExampleReActAgentFactory.build(
                "smoke-agent", "Smoke Agent", "Agent for skill smoke test", llmConfig);

        // 3. Ensure the agent has a non-null sysOperationId so that BaseAgent's
        //    lazyInitSkill() actually instantiates SkillUtil. The factory-built
        //    ReActAgentConfig leaves sysOperationId null, which makes registerSkill
        //    a no-op (SkillUtil stays null and hasSkill() returns false).
        Object config = agent.getConfig();
        assertThat(config)
                .as("agent.getConfig() should return a ReActAgentConfig")
                .isInstanceOf(ReActAgentConfig.class);
        if (config instanceof ReActAgentConfig) {
            ReActAgentConfig reactConfig = (ReActAgentConfig) config;
            reactConfig.setSysOperationId("smoke-agent");
        }

        // 4. Register the skill BEFORE query (must happen on the request thread,
        //    SkillManager is not thread-safe; here the test thread IS the request thread)
        agent.registerSkill(skillDir.toString());

        boolean hasSkill = agent.getSkillUtil() != null && agent.getSkillUtil().hasSkill();
        assertThat(hasSkill)
                .as("SkillUtil should report hasSkill=true after registerSkill")
                .isTrue();

        // 5. Wrap agent into runtime handler boundary and query
        AgentHandler handler = new JiuwenCoreAgentHandler(agent, ExternalSvcAdapterRegistrar.noop());
        QueryResponse response = handler.query(newServeRequest("你有哪些可用的技能？请列出名称。"));

        // 6. Extract assistant text from result map
        String responseText = assistantText(response);

        // 7. Assert the unique marker from SKILL.md appears in the response
        assertThat(responseText)
                .as("LLM response should contain the skill marker '%s' (proves SKILL.md reached the prompt). Response: %s",
                        UNIQUE_MARKER, responseText)
                .contains(UNIQUE_MARKER);
    }

    private static ServeRequest newServeRequest(String userQuery) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("smoke-conv-1");
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userQuery);
        request.setMessages(List.of(userMessage));
        return request;
    }

    private static String assistantText(QueryResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        Object result = response.getResult();
        if (result instanceof Map<?, ?> map) {
            Object content = map.get("content");
            if (content != null) {
                return String.valueOf(content);
            }
            Object output = map.get("output");
            if (output != null) {
                return String.valueOf(output);
            }
            Object responseVal = map.get("response");
            if (responseVal != null) {
                return String.valueOf(responseVal);
            }
        }
        return String.valueOf(result);
    }
}
