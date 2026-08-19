/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.agents.intent.spi.IntentResultFunction;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/** Tests that every source change is applied through one complete catalog input. */
class IntentCatalogReplacementTest {
    private static final IntentResultFunction RETURN_ID = context -> new ReturnAction(
            context.selectedIntent().orElseThrow().id());

    @Test
    void fullInputAddsModifiesAndDeletesCustomIntentsAndFallback() {
        IntentSuite suite = suite();
        long first = suite.replaceCatalog(new IntentCatalogInput(List.of(),
                List.of(registration("order", "query order"), registration("notice", "service notice")),
                registration("fallback", "")));
        assertThat(first).isEqualTo(1L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("order", "notice");
        assertThat(suite.snapshot().initializedIntents().fallback().id()).isEqualTo("fallback");

        long second = suite.replaceCatalog(new IntentCatalogInput(List.of(),
                List.of(registration("notice", "updated notice"), registration("weather", "query weather")), null));
        assertThat(second).isEqualTo(2L);
        assertThat(suite.snapshot().version()).isEqualTo(2L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("notice", "weather");
        assertThat(suite.snapshot().initializedIntents().matchableIntents().get(0).description())
                .isEqualTo("updated notice");
        assertThat(suite.snapshot().initializedIntents().fallback()).isNull();
    }

    @Test
    void fullInputAddsAndDeletesAgentCardSources() {
        IntentSuite suite = suite();
        AgentCardInput bank = new AgentCardInput(card("Bank Agent", List.of(skill("balance"), skill("transfer"))),
                "bank-agent");
        AgentCardInput wealth = new AgentCardInput(card("Wealth Agent", List.of(skill("recommend"))), "wealth-agent");

        suite.replaceCatalog(new IntentCatalogInput(List.of(bank), List.of(), null));
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("a2a:bank-agent:balance", "a2a:bank-agent:transfer");

        long version = suite.replaceCatalog(new IntentCatalogInput(List.of(wealth), List.of(), null));
        assertThat(version).isEqualTo(2L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("a2a:wealth-agent:recommend");
    }

    private static IntentSuite suite() {
        return IntentSuite.builder(IntentSuiteConfig.defaults()).initializer(new DefaultIntentInitializer())
                .matcher(context -> Optional.empty()).build();
    }

    private static CustomIntentRegistration registration(String id, String description) {
        return new CustomIntentRegistration(id, description, RETURN_ID);
    }

    private static AgentCard card(String name, List<AgentSkill> skills) {
        return AgentCard.builder().name(name).description(name + " operations").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain")).skills(skills)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://agent/a2a", null, "1.0"))).build();
    }

    private static AgentSkill skill(String id) {
        return new AgentSkill(id, id + " skill", id + " request", List.of("banking"), List.of(id + " example"), null,
                null, null);
    }
}
