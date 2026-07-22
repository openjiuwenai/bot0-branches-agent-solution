/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import com.openjiuwen.agents.reactrails.observability.ReactRailsObservability;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

/**
 * Library-tier factory: turn {@link VerifyAgentProperties} into a configured
 * {@link ReActAgent} that acts as a pure LLM judge — no tools registered.
 *
 * <p>Rail wiring is done here <em>manually</em> — the PR removed the Spring Boot
 * auto-config in the {@code refactor: separate extension sdk boundaries} commit,
 * so consumers now attach rails themselves. This is actually cleaner: what's on
 * the agent is visible in one place instead of hidden behind a BeanPostProcessor.
 *
 * <p>Two rails are attached:
 * <ul>
 *   <li>{@link CriteriaReplanBridgeRail} + {@link RuleBasedCriteriaVerifier} —
 *       PASS→forceFinish(verified) / FAIL→steer→retry / exhausted→forceFinish(degraded)</li>
 *   <li>{@link RootCauseRail} — belt-and-braces: if the underlying model client
 *       throws (network / gateway 5xx), degrade instead of spinning maxIterations</li>
 * </ul>
 *
 * @since 2026-07-14
 */
public final class VerifyAgentFactory {
    private VerifyAgentFactory() {
    }

    /**
     * Builds a {@link ReActAgent} configured as a judge with react-rails cognitive
     * guards attached.
     *
     * @param props the verify-agent configuration
     * @return the configured agent
     */
    public static ReActAgent build(VerifyAgentProperties props) {
        props.requireConfigured();

        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getMaxIterations())
                .sysOperationId(props.getSysOperationId())
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt())))
                .build()
                .configureModelClient(
                        props.getProvider(),
                        props.getApiKey(),
                        props.getApiBase(),
                        props.getModelName(),
                        props.isSslVerify());
        if (config.getModelConfigObj() != null) {
            config.getModelConfigObj().setTemperature(props.getTemperature());
            config.getModelConfigObj().setTopP(props.getTopP());
        }
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name(props.getAgentName())
                .description(props.getAgentDescription())
                .build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);

        ReplanRail sharedReplanCounter = new ReplanRail(props.getMaxReplan());
        RuleBasedCriteriaVerifier base = new RuleBasedCriteriaVerifier();
        org.slf4j.Logger factLog = org.slf4j.LoggerFactory.getLogger(VerifyAgentFactory.class);
        com.openjiuwen.agents.reactrails.verification.CriteriaVerifier loggingVerifier =
                (criteria, output, decisionHistory) -> {
                    var violations = base.verify(criteria, output, decisionHistory);
                    factLog.info("verifier.verify() called: criteria={}, output={}, violations={}",
                            criteria, output, violations);
                    return violations;
                };
        agent.registerRail(new CriteriaReplanBridgeRail(
                loggingVerifier,
                props.getCriteria(),
                sharedReplanCounter));
        agent.registerRail(new RootCauseRail());
        ReactRailsObservability.install(agent);
        factLog.info("verify-agent rails wired: criteria={}, maxReplan={}, maxIterations={}, observability=on",
                props.getCriteria(), props.getMaxReplan(), props.getMaxIterations());
        return agent;
    }
}
