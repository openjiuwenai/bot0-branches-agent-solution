/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.spi.IntentMatcher;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Tests the shared Agent Card result function through the suite execution path. */
class A2ADelegateIntentResultFunctionTest {
    @Test
    void routesEachSelectedCardIntentUsingOnlyItsArgumentsAndTheSemantic() {
        AtomicReference<String> wantedIntent = new AtomicReference<>();
        IntentMatcher byId = context -> context.catalogSnapshot().initializedIntents().matchableIntents().stream()
                .filter(intent -> intent.id().equals(wantedIntent.get())).findFirst();
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(byId).build();
        suite.replaceCatalog(
                new IntentCatalogInput(
                        List.of(new AgentCardInput(card("Bank Agent", "balance"), "bank-agent"),
                                new AgentCardInput(card("Wealth Agent", "recommend"), "wealth-agent")),
                        List.of(), null));
        List<IntentDefinition> intents = suite.snapshot().initializedIntents().matchableIntents();
        assertThat(intents.get(0).resultFunction()).isSameAs(intents.get(1).resultFunction());

        wantedIntent.set("a2a:bank-agent:balance");
        assertDelegates(suite.resolve(Map.of("semantic", "查询余额"), Map.of()), "bank-agent", "查询余额");
        wantedIntent.set("a2a:wealth-agent:recommend");
        assertDelegates(suite.resolve(Map.of("semantic", "推荐理财"), Map.of()), "wealth-agent", "推荐理财");
    }

    @Test
    void failsWhenTheSelectedIntentLacksDelegateArguments() {
        IntentMatcher first = context -> Optional
                .of(context.catalogSnapshot().initializedIntents().matchableIntents().get(0));
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(first).build();
        suite.replaceCatalog(new IntentCatalogInput(List.of(), List.of(
                new CustomIntentRegistration("custom", "custom description", new A2ADelegateIntentResultFunction())),
                null));

        assertThat(suite.resolve(Map.of("semantic", "route"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);
    }

    private static void assertDelegates(IntentDecision decision, String agentName, String semantic) {
        assertThat(decision.status()).isEqualTo(IntentDecisionStatus.MATCHED);
        if (!(decision.action() instanceof InvokeToolAction action)) {
            throw new AssertionError("expected an a2a_delegate action");
        }
        assertThat(action.toolName()).isEqualTo(A2ADelegateIntentResultFunction.TOOL_NAME);
        // Exactly the delegate target and the routing semantic; no history or kwargs forwarding.
        assertThat(action.arguments()).containsOnlyKeys("agentName", "remoteInput")
                .containsEntry("agentName", agentName).containsEntry("remoteInput", semantic);
    }

    private static AgentCard card(String name, String skillId) {
        AgentSkill skill = new AgentSkill(skillId, skillId + " skill", skillId + " request", List.of("banking"),
                List.of(skillId + " example"), List.of("text"), null, null);
        return AgentCard.builder().name(name).description(name + " operations").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain")).skills(List.of(skill))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://agent/a2a", null, "1.0"))).build();
    }
}
