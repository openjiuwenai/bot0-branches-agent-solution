/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent;

import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Assembles the ReActAgent plan-agent and wraps it in the agent-solution
 * agentcore-ext handler. The remote versatile-adapter agent is wired as an A2A
 * interrupt-rail tool via {@code openjiuwen.service.a2a.remote-agents} (handled
 * by AgentCoreExtAutoConfiguration / RemoteA2aToolInstaller).
 *
 * <p>The decomposition logic (intent values, {@code remoteInput} JSON shape,
 * serial one-call-per-turn rule) lives entirely in {@link #SYSTEM_PROMPT} — the
 * skill subsystem is intentionally NOT used (it does not load reliably at SUT
 * runtime and is unnecessary for this single-purpose planner).
 *
 * @since 2026-07-08
 */
@Configuration(proxyBeanMethods = false)
public class PlanAgentConfiguration {
    static final String AGENT_ID = "plan-agent";

    private static final String SYSTEM_PROMPT = """
            You are a banking assistant. The user may ask several things in one sentence
            (check balance, transfer money to one or more people). Decompose the request
            into ordered atomic tasks and execute them STRICTLY ONE AT A TIME.

            ## Decomposition rules
            1. Balance queries come first. Any "查余额 / 查账户余额" task runs before any transfer.
            2. One task per (recipient, amount) pair. "转5元给李四和10元给王五" is two separate
               transfers — but each is its own turn, emitted one after the other, never together.
            3. For each task, call the `versatile-adapter` tool exactly once, with `remoteInput` set
               to a JSON string of the form:
               {"query": "<the concrete subtask in Chinese>", "intent": "<intent>"}

            ## Intent values (use exactly these strings)
            - 查询账户余额 — for any balance / 余额 / 查账户 task.
            - 快速转账 — for any transfer / 转账 / 转给 task.

            ## CRITICAL RULE — SERIAL EXECUTION (mandatory, never violate)
            - Issue **exactly ONE** `versatile-adapter` tool call per turn. NEVER place two or more
              `versatile-adapter` calls in the same response. No batching a balance query with a
              transfer, and no batching two transfers, in one turn.
            - After you emit a `versatile-adapter` call, STOP. Wait for its result to come back.
              Only after you have observed that result, decide and emit the next single
              `versatile-adapter` call.
            - One task must fully finish before the next one starts. No parallel, batched, or
              concurrent versatile-adapter invocations — the downstream versatile-adapter agent is
              stateful per conversation and cannot be called in parallel.

            ## Example (executed one call per turn, serially)
            User: "先查询尾号为4241的银行卡余额，再转账5元给李四"
            - Turn 1: call `versatile-adapter` once with
              remoteInput = {"query":"查询尾号为4241的银行卡余额","intent":"查询账户余额"} → STOP, wait for result.
            - Turn 2 (only after Turn 1's result is back): call `versatile-adapter` once with
              remoteInput = {"query":"从尾号为4241的银行卡转账5元给李四","intent":"快速转账"} → STOP, wait for result.
            Do NOT emit both calls in Turn 1. Each turn contains exactly one `versatile-adapter`.

            When all tasks are done, summarise every result for the user in Chinese (balances
            and transfer confirmations).
            """;

    @Bean
    AgentHandler planAgentHandler(
            @Value("${plan-agent.model-provider:openai}") String modelProvider,
            @Value("${plan-agent.api-key:}") String apiKey,
            @Value("${plan-agent.api-base:http://localhost:4000/v1}") String apiBase,
            @Value("${plan-agent.model-name:gpt-4o-mini}") String modelName,
            @Value("${plan-agent.ssl-verify:true}") boolean sslVerify) {
        return new JiuwenCoreAgentExtHandler(
                buildReActAgent(modelProvider, apiKey, apiBase, modelName, sslVerify));
    }

    static ReActAgent buildReActAgent(String modelProvider, String apiKey, String apiBase,
                                      String modelName, boolean sslVerify) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("Plan agent that decomposes a banking request and calls versatile-adapter per task.")
                .build();
        ReActAgent agent = new ReActAgent(card);

        // Only the versatile-adapter A2A delegate tool is exposed (wired via
        // openjiuwen.service.a2a.remote-agents). No filesystem / shell / skill
        // tools — this agent only plans and delegates.
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", SYSTEM_PROMPT)))
                .maxIterations(12)
                .build()
                .configureModelClient(modelProvider, apiKey, apiBase, modelName, sslVerify);
        ModelRequestConfig modelConfig = config.getModelConfigObj();
        modelConfig.setTemperature(0.0);
        modelConfig.setMaxTokens(512);
        agent.configure(config);

        return agent;
    }
}
