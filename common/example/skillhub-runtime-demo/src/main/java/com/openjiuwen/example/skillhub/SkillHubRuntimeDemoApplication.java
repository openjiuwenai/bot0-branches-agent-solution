/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.skillhub;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Demo that exercises the SkillHub middleware end-to-end:
 * <ul>
 *   <li>enables {@code SkillHubMiddlewareAutoConfiguration} (downloads + verifies + registers skills)</li>
 *   <li>when {@code openjiuwen.demo.llm.api-key} is set, assembles a ReActAgent whose
 *       query path triggers {@code SkillHubManager.register(agent)} so downloaded
 *       skills are handed to {@code BaseAgent.registerSkill}</li>
 * </ul>
 *
 * <p>Slice 1 tests run without LLM config and only assert SkillHub wiring.
 * Slice 2 (full lifecycle) supplies a real DeepSeek LLM via {@code @DynamicPropertySource}.
 *
 * @since 2026-07-15
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties({MiddlewareProperties.class, LlmProperties.class})
public class SkillHubRuntimeDemoApplication {
    private static final String AGENT_ID = "demo-skillhub-runtime-agent";

    public static void main(String[] args) {
        SpringApplication.run(SkillHubRuntimeDemoApplication.class, args);
    }

    /**
     * Full agent handler with SkillHub middleware. Only assembled when an LLM api-key
     * is configured, so the SkillHub-only slices still boot without an LLM.
     *
     * <p>{@link JiuwenCoreAgentExtHandler} (not {@code JiuwenCoreAgentHandler}) is used
     * because it has {@code @Autowired SkillHubManager} — when the skillhub middleware
     * is enabled, Spring injects the manager and the handler's {@code start()} triggers
     * {@code manager.download()} (background retry on failure), and {@code query()} triggers
     * {@code manager.register(agent)} on the request thread.
     */
    @Bean
    @ConditionalOnProperty(name = "openjiuwen.service.llm.api-key")
    AgentHandler agentHandler(LlmProperties llmProperties, LlmConfigResolver llmConfigResolver) {
        // Give the agent a system prompt that encourages using skills.
        // Must be set BEFORE resolveRequired() because LlmConfigResolver caches its result.
        if (llmProperties.getSystemPrompt() == null || llmProperties.getSystemPrompt().isBlank()) {
            llmProperties.setSystemPrompt(
                    "你是一个通用助手。系统已通过 SkillHub 为你注册了若干技能（skill）。"
                    + "请优先调用已注册的技能来回答用户问题。");
        }
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "SkillHub Runtime Demo Agent",
            "ReAct agent with SkillHub-downloaded skills", llmConfig);
        // ExampleReActAgentFactory.build does NOT set sysOperationId on the ReActAgentConfig.
        // BaseAgent.lazyInitSkill() reflects config.getSysOperationId() and returns early
        // when it is null, which makes registerSkill() a silent no-op (SkillUtil stays null,
        // hasSkill() returns false). Set it here so the SkillHub chain can actually register
        // downloaded skills into this agent's SkillManager.
        if (agent.getConfig() instanceof ReActAgentConfig) {
            ((ReActAgentConfig) agent.getConfig()).setSysOperationId(AGENT_ID);
        }
        // JiuwenCoreAgentExtHandler.setSkillHubManager is @Autowired(required=false);
        // Spring injects the SkillHubManager bean created by SkillHubMiddlewareAutoConfiguration.
        return new JiuwenCoreAgentExtHandler(agent);
    }
}
