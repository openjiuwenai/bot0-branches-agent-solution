/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.autoconfigure;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentRail;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for react-rails.
 *
 * <p>When Spring Boot is present + a {@link ReActAgent} bean exists in the context,
 * this auto-config registers the three cognitive rails onto the agent:
 * <ul>
 *   <li>{@link CriteriaVerificationRail} — requires {@code reactrails.criteria} property (comma-separated)</li>
 *   <li>{@link ReplanRail} — optional, maxReplan configurable via {@code reactrails.max-replan}</li>
 *   <li>{@link RootCauseRail} — always registered</li>
 *   <li>{@link ReplanTool} — registered via {@code agent.getAbilityManager().add(...)} if ReplanRail is active</li>
 * </ul>
 *
 * <p>Properties (application.properties/yml):
 * <pre>
 * reactrails.enabled=true                          # master switch (default true)
 * reactrails.criteria=给出配置建议,引用风险评估     # comma-separated criteria strings
 * reactrails.max-replan=2                         # max replan count (default 2)
 * </pre>
 */
@AutoConfiguration
@ConditionalOnClass({ReActAgent.class, AgentRail.class})
@ConditionalOnProperty(name = "reactrails.enabled", havingValue = "true", matchIfMissing = true)
public class ReactRailsAutoConfiguration {

    /**
     * Default rule-based criteria verifier (when no custom CriteriaVerifier bean exists).
     *
     * @return a {@link RuleBasedCriteriaVerifier} instance
     */
    @Bean
    @ConditionalOnMissingBean(CriteriaVerifier.class)
    public CriteriaVerifier defaultCriteriaVerifier() {
        return new RuleBasedCriteriaVerifier();
    }

    /**
     * BeanPostProcessor that registers cognitive rails onto every {@link ReActAgent} bean.
     *
     * <p>Uses a customizer pattern: the processor checks each bean; if it's a ReActAgent,
     * it registers the rails via {@code registerRail()} + {@code getAbilityManager().add()}.
     *
     * @param criteriaVerifier the injected verifier (default or custom)
     * @return the rail-registering post-processor
     */
    @Bean
    public BeanPostProcessor reactRailsRegistrar(
            CriteriaVerifier criteriaVerifier,
            ReactRailsProperties properties) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ReActAgent agent) {
                    // CriteriaVerificationRail (only if criteria configured)
                    if (properties.getCriteria() != null && !properties.getCriteria().isEmpty()) {
                        agent.registerRail(new CriteriaVerificationRail(
                                criteriaVerifier, properties.getCriteria()));
                    }
                    // ReplanRail + ReplanTool
                    if (properties.getMaxReplan() >= 0) {
                        agent.registerRail(new ReplanRail(properties.getMaxReplan()));
                        ReplanTool.registerOnto(agent);
                    }
                    // RootCauseRail (always)
                    agent.registerRail(new RootCauseRail());
                }
                return bean;
            }
        };
    }

    /**
     * Properties bean for react-rails configuration.
     *
     * @return the properties holder
     */
    @Bean
    @ConditionalOnMissingBean
    public ReactRailsProperties reactRailsProperties() {
        return new ReactRailsProperties();
    }
}