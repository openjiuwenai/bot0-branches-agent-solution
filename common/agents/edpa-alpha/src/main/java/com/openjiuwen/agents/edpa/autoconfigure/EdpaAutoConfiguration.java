/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import com.openjiuwen.agents.edpa.EdpaRails;
import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Spring Boot auto-configuration for EDPA-alpha — infrastructure beans only.
 *
 * <p><b>C2 refactor (strategy B)</b>: the BeanPostProcessor rail-assembly logic has been
 * REMOVED. The auto-config now provides only the three infrastructure beans
 * ({@link EdpaProperties}, {@link CriteriaVerifier}, {@link Explorer}) — mirroring the
 * official {@code agent-service-app} pattern of "assemble infrastructure, not agents".
 *
 * <p><b>Assembly is explicit</b>: hosts following the official demo pattern
 * ({@code @Bean AgentHandler} with agent not exposed as a Spring bean) call
 * {@link EdpaRails#registerOnto} directly:
 * <pre>{@code
 * @Bean
 * AgentHandler myAgentHandler(LlmConfigResolver r, EdpaProperties props,
 *         CriteriaVerifier verifier, Explorer explorer) {
 *     ReActAgent agent = ExampleReActAgentFactory.build("my-agent", ..., llm);
 *     EdpaRails.registerOnto(agent, props, verifier, explorer);
 *     return new JiuwenCoreAgentHandler(agent);
 * }
 * }</pre>
 *
 * <p>A zero-hit WARN is emitted when {@code edpa.enabled=true} but no
 * {@link ReActAgent} bean was detected in the Spring context (hosts following the
 * official pattern should use {@link EdpaRails#registerOnto} instead).
 *
 * @since 2026-07
 */
@AutoConfiguration
@ConditionalOnClass(name = {"com.openjiuwen.core.singleagent.agents.ReActAgent",
        "com.openjiuwen.core.singleagent.rail.AgentRail"})
@ConditionalOnProperty(name = "edpa.enabled", havingValue = "true")
public class EdpaAutoConfiguration {
    private static final Logger LOG = Logger.getLogger(EdpaAutoConfiguration.class.getName());

    /**
     * Properties bean for EDPA configuration.
     *
     * @return the properties holder
     */
    @Bean
    @ConditionalOnMissingBean
    public EdpaProperties edpaProperties() {
        return new EdpaProperties();
    }

    /**
     * Default criteria verifier — keyword-based ({@link RuleBasedCriteriaVerifier}).
     *
     * <p>C5 refactor: the default bean is now {@link RuleBasedCriteriaVerifier}
     * (the actual behavior when no {@code DeterministicChecker} is injected).
     * Hosts wanting deterministic verification should override with
     * {@code new GroundTruthVerifier(List.of(myChecker))}.
     *
     * @return a {@link RuleBasedCriteriaVerifier} instance (keyword fallback, zero LLM)
     */
    @Bean
    @ConditionalOnMissingBean(CriteriaVerifier.class)
    public CriteriaVerifier edpaCriteriaVerifier() {
        return new RuleBasedCriteriaVerifier();
    }

    /**
     * Default {@link Explorer} bean — an {@link LlmExplorer} backed by the
     * context's {@link Model} (lazily resolved).
     *
     * @param properties EDPA properties (provides budget)
     * @param modelProvider lazy provider of the agent's Model bean
     * @return an LLM-backed explorer, or an empty-string explorer when no Model
     */
    @Bean
    @ConditionalOnMissingBean(Explorer.class)
    public Explorer edpaExplorer(EdpaProperties properties, ObjectProvider<Model> modelProvider) {
        ExploreBudget budget = properties.toExploreBudget();
        Function<String, String> llmFn = modelExploringFunction(modelProvider);
        return new LlmExplorer(llmFn, budget);
    }

    /**
     * Zero-hit detector: WARNs when {@code edpa.enabled=true} but EDPA rails are
     * absent from the Spring context.
     *
     * <p>Covers both failure modes:
     * <ul>
     *   <li><b>0 ReActAgent beans</b> — official demo pattern (agent inside
     *       {@code @Bean AgentHandler}); host should call
     *       {@link EdpaRails#registerOnto} explicitly.</li>
     *   <li><b>ReActAgent beans present but no EDPA rails</b> — hosts upgrading from
     *       the old BeanPostProcessor auto-assembly. The old BPP registered rails
     *       automatically on every ReActAgent bean; C2 removed this. The host must
     *       now call {@link EdpaRails#registerOnto} in their own
     *       {@code @Bean AgentHandler} method (breaking change).</li>
     * </ul>
     *
     * <p><b>Honest boundary</b>: the detector cannot verify that a host following
     * the demo pattern called {@code registerOnto} — static calls leave no
     * Spring-observable footprint. The zero-bean WARN is the best available signal.
     *
     * @return the context-refreshed listener
     */
    @Bean
    public ApplicationListener<ContextRefreshedEvent> edpaZeroHitDetector() {
        return event -> {
            var beans = event.getApplicationContext().getBeansOfType(ReActAgent.class);
            if (beans.isEmpty()) {
                LOG.warning("EDPA enabled but no ReActAgent bean detected in the Spring context. "
                        + "If you follow the official demo pattern (agent instantiated inside "
                        + "@Bean AgentHandler), call EdpaRails.registerOnto(agent, props, "
                        + "verifier, explorer) explicitly.");
            } else {
                // ReActAgent beans exist — check if any has EDPA rails registered.
                // Old BPP auto-registered on every bean; C2 removed this. If none have
                // EDPA rails, the host likely upgraded without adding registerOnto calls.
                long withEdpaRails = beans.values().stream()
                        .filter(agent -> hasEdpaRail(agent))
                        .count();
                if (withEdpaRails == 0) {
                    LOG.warning("EDPA enabled but " + beans.size() + " ReActAgent bean(s) have "
                            + "no EDPA rails registered. The BeanPostProcessor auto-assembly "
                            + "was removed (breaking change). Call EdpaRails.registerOnto(agent, "
                            + "props, verifier, explorer) in your @Bean AgentHandler method.");
                }
            }
        };
    }

    /**
     * Probes whether a ReActAgent has any EDPA-characteristic rail registered.
     *
     * <p>Uses the ability manager's tool list to detect the {@code __replan__} tool
     * (registered by {@code ReplanTool.registerOnto} during EDPA assembly) — an
     * observable proxy for "EDPA rails were registered on this agent".
     *
     * @param agent the agent to probe
     * @return true if EDPA assembly is detected
     */
    private static boolean hasEdpaRail(ReActAgent agent) {
        try {
            return agent.getAbilityManager().listToolInfo().stream()
                    .anyMatch(t -> "__replan__".equals(t.getName()));
        } catch (Exception e) {
            return false;
        }
    }

    // ==================================================================
    // Model → Function<String,String> adapter
    // ==================================================================

    /**
     * Builds the {@code prompt → response} function that backs
     * {@link LlmExplorer}, delegating to {@link Model#invoke}.
     *
     * <p>{@link Model#invoke} declares {@code throws Exception}; a
     * {@link Function} cannot propagate checked exceptions, so failures are
     * caught and mapped to "" — {@link LlmExplorer} then treats blank output as
     * "no findings" (honest degradation, no fake steering).
     *
     * <p>The Model is resolved lazily from the {@link ObjectProvider} on each
     * call, so this function is safe to construct at auto-config time even
     * though {@code agent.setLlm} runs later.
     *
     * <p>Package-private so e2e wiring tests can exercise the same adapter the
     * auto-config uses (no production caller outside this class).
     *
     * @param modelProvider lazy Model provider
     * @return a prompt→response function, or a no-op when no Model is available
     */
    static Function<String, String> modelExploringFunction(ObjectProvider<Model> modelProvider) {
        return prompt -> {
            Model model = modelProvider.getIfAvailable();
            if (model == null) {
                // No Model bean in context — ExploreRail will skip (empty findings).
                return "";
            }
            List<BaseMessage> messages = Collections.singletonList(new UserMessage(prompt));
            AssistantMessage resp = invokeModel(model, messages);
            return resp == null ? "" : resp.getContentAsString();
        };
    }

    /**
     * Invokes the SDK model synchronously while adapting its broad checked-exception contract.
     *
     * @param model model to invoke
     * @param messages prompt messages
     * @return the model response
     */
    private static AssistantMessage invokeModel(Model model, List<BaseMessage> messages) {
        FutureTask<AssistantMessage> invocation = new FutureTask<>(
                () -> model.invoke(messages, null, null, null, null, null, null, null, null, null));
        invocation.run();
        try {
            return invocation.get();
        } catch (InterruptedException e) {
            throw new IllegalStateException("exploring model invoke interrupted", e);
        } catch (ExecutionException e) {
            throw modelInvocationFailure(e.getCause());
        }
    }

    /**
     * Preserves unchecked model failures and wraps only checked failures for the {@link Function} boundary.
     *
     * @param cause model invocation failure
     * @return wrapper for a checked model failure
     */
    private static IllegalStateException modelInvocationFailure(Throwable cause) {
        if (cause instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("exploring model invoke failed: " + cause.getMessage(), cause);
    }
}
