/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Plan-agent configuration, bound under {@code plan-agent.*}. Bundles the LLM
 * client fields, the system-prompt resource and the output token budget into one
 * typed record so the {@code planAgentHandler} bean takes a single argument
 * instead of a long {@code @Value} parameter list.
 *
 * <p>{@code promptResource} defaults to the SERIAL decomposition prompt
 * ({@code prompts/plan-agent-system-prompt.md}); the {@code parallel-transfer}
 * profile overrides it with the parallel variant
 * ({@code prompts/plan-agent-system-prompt-parallel.md}).
 *
 * @since 2026-07-08
 */
@ConfigurationProperties(prefix = "plan-agent")
public record PlanAgentProperties(
        @DefaultValue("openai") String modelProvider,
        @DefaultValue("") String apiKey,
        @DefaultValue("http://localhost:4000/v1") String apiBase,
        @DefaultValue("gpt-4o-mini") String modelName,
        @DefaultValue("true") boolean sslVerify,
        @DefaultValue("classpath:prompts/plan-agent-system-prompt.md") String promptResource,
        @DefaultValue("512") int maxTokens) {
}
