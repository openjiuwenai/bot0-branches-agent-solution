/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

/**
 * Real LLM e2e test OUTLINE for FIRST_PRINCIPLES injection mode.
 *
 * <p>This is an outline/skeleton — NOT auto-executable. To run, copy the
 * body patterns from sibling tests (e.g. {@code CriteriaBridgeRealLlmE2eTest})
 * and execute via {@code run-real-llm-e2e.sh} with a real API key.
 *
 * <h3>Test strategy</h3>
 * <ol>
 *   <li><b>TREATMENT (FIRST_PRINCIPLES mode):</b>
 *     <ul>
 *       <li>Create {@link com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel}
 *           with a real LLM client config (deepseek-v4-flash / deepseek-v4-pro / Qwen3.5-35B).</li>
 *       <li>Set {@code InjectionMode.FIRST_PRINCIPLES}.</li>
 *       <li>Wire to ReActAgent + PevReplanRail + ReplanTool.</li>
 *       <li>Invoke with a multi-faceted analysis prompt (e.g. "分析当前的经济形势").</li>
 *       <li>Hard assertion: {@code rail.replanCount() >= 1} — the injected
 *           "先扩后收" prompt should trigger self-reflection + replanning.</li>
 *       <li>Soft observation: agent output contains {@code "先扩后收"} or
 *           {@code "第一性原理"} markers (printed to stdout, not asserted).</li>
 *     </ul>
 *   </li>
 *   <li><b>CONTROL (NONE mode):</b>
 *     <ul>
 *       <li>Same setup but with {@code NONE} mode (default).</li>
 *       <li>Observe: replanCount is typically 0 without the first-principles injection
 *           (printed to stdout, not hard-asserted — model variance exists).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Hard assertions vs soft observations</h3>
 * <table>
 *   <tr><th>Signal</th><th>Type</th><th>Rationale</th></tr>
 *   <tr><td>result != null</td><td>Hard</td><td>Agent must complete</td></tr>
 *   <tr><td>output.length() &gt; 50</td><td>Hard</td><td>Non-trivial output</td></tr>
 *   <tr><td>rail.replanCount() &gt;= 1</td><td>Hard (flaky)</td><td>FIRST_PRINCIPLES prompt must nudge replan;
 *       flaky across models — deepseek-v4-flash is more cooperative than Qwen3.5-35B</td></tr>
 *   <tr><td>output contains "先扩后收"</td><td>Soft</td><td>The injected prompt text may or may not
 *       appear in final output (depends on model/thinking mode)</td></tr>
 *   <tr><td>replanCount == 0 (NONE mode)</td><td>Soft</td><td>Control observation — not guaranteed</td></tr>
 * </table>
 *
 * <h3>Flakiness notes</h3>
 * <ul>
 *   <li>deepseek-v4-flash sometimes answers directly without replanning even with the
 *       FIRST_PRINCIPLES prompt — this is acceptable behavioral variance (the prompt
 *       nudges, doesn't force).</li>
 *   <li>deepseek-v4-pro with thinking enabled may produce more replanning but slower
 *       execution. Use {@code LLM_THINKING=thinking-off} to disable.</li>
 *   <li>Qwen3.5-35B tends to be more compliant but may not spontaneous replan.</li>
 *   <li>The probe (in ToolCallingEnforcingModel) adds one extra LLM call before the
 *       real invocation — this is transparent to the agent behavior.</li>
 * </ul>
 *
 * <h3>Running</h3>
 * <pre>{@code
 *   # Set env:
 *   export OPENJIUWEN_API_KEY=...
 *   export OPENJIUWEN_BASE_URL=...
 *   export OPENJIUWEN_MODEL=deepseek-v4-flash   # or deepseek-v4-pro, glm-5.2, etc.
 *   export LLM_THINKING=thinking-off            # optional
 *
 *   # Run via shell (not mvn test — env-gated):
 *   cd agent-patterns/react-rails
 *   mvn test -Dtest=FirstPrinciplesE2eTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 *
 * <h3>Expected outcomes</h3>
 * <table>
 *   <tr><th>Model</th><th>FIRST_PRINCIPLES replan?</th><th>NONE replan?</th></tr>
 *   <tr><td>deepseek-v4-flash</td><td>Usually yes (1-3)</td><td>Usually 0</td></tr>
 *   <tr><td>deepseek-v4-pro</td><td>Yes, more thorough</td><td>Rarely 1</td></tr>
 *   <tr><td>Qwen3.5-35B</td><td>Sometimes 1</td><td>Almost never</td></tr>
 *   <tr><td>glm-5.2</td><td>Rarely (moderation interference)</td><td>Never</td></tr>
 * </table>
 *
 * @see com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel
 * @see com.openjiuwen.agents.reactrails.replan.PevReplanRail
 */
public class FirstPrinciplesE2eTest {

    /**
     * Outline: copy this method to a real test class and fill in the LLM
     * client config from {@link LlmClient} env vars.
     *
     * <pre>{@code
     *   SystemPromptInjectingModel.resetToDefaults();
     *   var model = new SystemPromptInjectingModel(cliCfg, reqCfg);
     *   SystemPromptInjectingModel.setInjectionMode(
     *           SystemPromptInjectingModel.InjectionMode.FIRST_PRINCIPLES);
     *
     *   ReActAgent agent = new ReActAgent(AgentCard.builder().name("fp-e2e").build());
     *   agent.setLlm(model);
     *   ReplanTool.registerOnto(agent);
     *   PevReplanRail rail = new PevReplanRail(5);
     *   agent.registerRail(rail);
     *
     *   Object result = agent.invoke("分析当前的经济形势...", null);
     *
     *   assertThat(result).isNotNull();
     *   // Hard(ish): FIRST_PRINCIPLES should nudge replan
     *   assertThat(rail.replanCount()).isGreaterThanOrEqualTo(1);
     *   // Soft: output might contain the injected marker
     *   System.out.println("replanCount: " + rail.replanCount());
     * }</pre>
     */
    void firstPrinciples_replanActivates_outline() {
        // See javadoc above for implementation.
        // LlmClient.envPresent() → gating check.
    }

    /**
     * Outline: control condition with NONE mode.
     *
     * <pre>{@code
     *   SystemPromptInjectingModel.resetToDefaults();
     *   // Mode stays NONE (default) — no injection
     *   var model = new SystemPromptInjectingModel(cliCfg, reqCfg);
     *   // ... same ReActAgent setup ...
     *
     *   assertThat(rail.replanCount()).isGreaterThanOrEqualTo(0);
     *   System.out.println("NONE replanCount: " + rail.replanCount());
     * }</pre>
     */
    void noneMode_baseline_outline() {
        // See javadoc above for implementation.
    }
}
