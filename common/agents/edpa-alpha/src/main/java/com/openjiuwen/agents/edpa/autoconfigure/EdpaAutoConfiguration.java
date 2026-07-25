/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.agents.edpa.rail.ExploreRail;
import com.openjiuwen.agents.edpa.rail.UserInputCaptureRail;
import com.openjiuwen.agents.edpa.verification.GroundTruthVerifier;
import com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Spring Boot auto-configuration for EDPA-alpha.
 *
 * <p><b>Species B-replace-injected</b>: when {@code edpa.enabled=true}, this
 * auto-config REPLACES react-rails by registering the full EDPA rail stack onto
 * every {@link ReActAgent}:
 * <ul>
 *   <li>{@link ExploreRail} (new) — Explore phase: explore + pushSteering</li>
 *   <li>{@link ReplanRail} (reused) — Decision: __replan__ dispatch</li>
 *   <li>{@link CriteriaReplanBridgeRail} (reused) — Action verify gate</li>
 *   <li>{@link RootCauseRail} (reused) — Action device-failure degrade</li>
 * </ul>
 *
 * <p><b>LlmExplorer LLM access</b>: the Explorer SPI takes a
 * {@code Function<String,String>}. This auto-config builds that function from a
 * context-provided {@link Model} bean (lazy-resolved via
 * {@link ObjectProvider} so the Model need not exist at BeanPostProcessor time —
 * it is only touched when {@code explore()} actually runs, by which point
 * {@code agent.setLlm(model)} has long since executed). If no {@link Model} bean
 * exists, a no-op explorer function (returns "") is wired — honest degradation,
 * ExploreRail then skips pushSteering.
 *
 * @since 2026-07
 */
@AutoConfiguration
@ConditionalOnClass(name = {"com.openjiuwen.core.singleagent.agents.ReActAgent",
        "com.openjiuwen.core.singleagent.rail.AgentRail"})
@ConditionalOnProperty(name = "edpa.enabled", havingValue = "true")
public class EdpaAutoConfiguration {
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
     * Default rule-based criteria verifier (mirrors react-rails default).
     *
     * @return a {@link RuleBasedCriteriaVerifier} instance
     */
    @Bean
    @ConditionalOnMissingBean(CriteriaVerifier.class)
    public CriteriaVerifier edpaCriteriaVerifier() {
        return new GroundTruthVerifier();
    }

    /**
     * Default {@link Explorer} bean — an {@link LlmExplorer} backed by the
     * context's {@link Model} (lazily resolved so it survives the
     * BeanPostProcessor-before-setLlm timing window).
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
     * BeanPostProcessor that registers the EDPA rail stack onto every
     * {@link ReActAgent}. Mirrors react-rails' registrar shape but registers
     * ExploreRail in addition to the 3 reused rails.
     *
     * <p><b>Timing note</b>: the Explorer bean is captured by reference here;
     * its internal {@code ObjectProvider<Model>} is only resolved at
     * {@code explore()} time (during the agent loop, well after
     * {@code setLlm}), so the SDK's setLlm-after-init ordering is a non-issue.
     *
     * @param properties EDPA configuration
     * @param criteriaVerifier injected verifier
     * @param explorer the Explore-phase SPI
     * @return the rail-registering post-processor
     */
    @Bean
    public BeanPostProcessor edpaRegistrar(EdpaProperties properties, CriteriaVerifier criteriaVerifier,
            Explorer explorer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof ReActAgent agent)) {
                    return bean;
                }
                ExploreBudget budget = properties.toExploreBudget();
                String exploreMode = properties.getExploreMode();
                boolean useToolMode = !"rail".equalsIgnoreCase(exploreMode);

                if (useToolMode) {
                    AtomicReference<String> userInputRef = new AtomicReference<>();
                    agent.registerRail(new UserInputCaptureRail(userInputRef));
                    ExploreToolRegistrar.registerOnto(agent, explorer, budget,
                            () -> userInputRef.get());
                } else {
                    agent.registerRail(new ExploreRail(explorer, budget));
                }
                ReplanRail sharedReplanRail = new ReplanRail(properties.getMaxReplan());
                if (!properties.getCriteria().isEmpty()) {
                    agent.registerRail(new CriteriaReplanBridgeRail(
                            criteriaVerifier, properties.getCriteria(), sharedReplanRail));
                    if (properties.isProactiveConvergenceEnabled()) {
                        agent.registerRail(buildProactiveConvergenceRail(
                                criteriaVerifier, properties));
                    }
                }
                if (properties.getMaxReplan() >= 0) {
                    // Register the SAME instance that was passed to CriteriaReplanBridgeRail above —
                    // LLM-driven __replan__ calls and verify-failure retries must share one budget
                    // (4-lens MAJOR #1 fix: was `new ReplanRail(...)` = disjoint counters → 2× budget).
                    agent.registerRail(sharedReplanRail);
                    ReplanTool.registerOnto(agent);
                }
                // Action phase — device-failure degrade (reused).
                agent.registerRail(new RootCauseRail());
                return bean;
            }
        };
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

    /**
     * Builds the {@link ProactiveConvergenceRail} (action-phase convergence
     * monitor) from the configured success criteria and stall window. Extracted
     * so the registrar stays readable; behavior is unchanged.
     *
     * @param verifier criteria verifier shared with the bridge rail
     * @param properties EDPA configuration (criteria + stall window)
     * @return a configured convergence rail
     */
    private static ProactiveConvergenceRail buildProactiveConvergenceRail(
            CriteriaVerifier verifier, EdpaProperties properties) {
        return new ProactiveConvergenceRail(verifier, properties.getCriteria(),
                properties.getProactiveConvergenceStallWindow(),
                ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL);
    }
}
