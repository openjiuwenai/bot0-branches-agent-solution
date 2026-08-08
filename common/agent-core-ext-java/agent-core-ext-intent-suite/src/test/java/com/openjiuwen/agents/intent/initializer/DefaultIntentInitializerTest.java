/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.initializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.spi.IntentResultFunction;
import com.openjiuwen.agents.intent.model.A2ADelegateArguments;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.ReturnAction;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Tests Agent Card and custom intent initialization. */
class DefaultIntentInitializerTest {
    @Test
    void createsCardSkillIntentsWithSharedResultFunction() {
        IntentResultFunction sharedA2a = context -> new ReturnAction(context.resultArguments());
        DefaultIntentInitializer initializer = new DefaultIntentInitializer(sharedA2a);
        AgentCard card = card(List.of(skill("balance", null), skill("transfer", List.of("text/plain")),
                skill("voice", List.of("audio/wav"))));
        CustomIntentRegistration custom = new CustomIntentRegistration("date", "query date",
                context -> new ReturnAction("date"));
        CustomIntentRegistration fallback = new CustomIntentRegistration("fallback", "",
                context -> new ReturnAction("retry"));

        InitializedIntents initialized = initializer.initialize(IntentSuiteConfig.defaults(),
                new IntentCatalogInput(List.of(new AgentCardInput(card, "bank-agent")), List.of(custom), fallback));

        assertThat(initialized.matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("a2a:bank-agent:balance", "a2a:bank-agent:transfer", "date");
        IntentDefinition balance = initialized.matchableIntents().get(0);
        IntentDefinition transfer = initialized.matchableIntents().get(1);
        assertThat(balance.resultFunction()).isSameAs(sharedA2a);
        assertThat(transfer.resultFunction()).isSameAs(sharedA2a);
        assertThat(balance.resultArguments()).isSameAs(transfer.resultArguments())
                .isEqualTo(new A2ADelegateArguments("bank-agent"));
        assertThat(balance.description()).contains("Bank Agent", "balance", "banking", "how much money");
        assertThat(initialized.fallback().id()).isEqualTo("fallback");
    }

    private static AgentCard card(List<AgentSkill> skills) {
        return AgentCard.builder().name("Bank Agent").description("Banking operations").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain")).skills(skills)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://bank/a2a", null, "1.0"))).build();
    }

    private static AgentSkill skill(String id, List<String> inputModes) {
        return new AgentSkill(id, id + " skill", id + " request", List.of("banking"),
                List.of("balance".equals(id) ? "how much money" : "send money"), inputModes, null, null);
    }
}
