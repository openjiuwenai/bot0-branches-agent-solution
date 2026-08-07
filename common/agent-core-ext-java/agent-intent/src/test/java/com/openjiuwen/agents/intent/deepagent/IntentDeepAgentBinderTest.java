/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/** Tests DeepAgent intent Rail binding. */
class IntentDeepAgentBinderTest {
    @Test
    void bindsOnceAndRejectsConflictingSuiteOrToolName() {
        IntentDeepAgentBinder binder = new IntentDeepAgentBinder();
        DeepAgent agent = new DeepAgent(null, DeepAgentConfig.builder().build(), null);
        IntentSuite first = suite();

        binder.bind(agent, first);
        binder.bind(agent, first);

        assertThat(agent.getAgent().getAbilityManager().list())
                .filteredOn(ability -> ability instanceof com.openjiuwen.core.foundation.tool.ToolCard card
                        && IntentRoutingRail.TOOL_NAME.equals(card.getName()))
                .hasSize(1);
        assertThatThrownBy(() -> binder.bind(agent, suite())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another IntentSuite");

        DeepAgent conflicting = new DeepAgent(null, DeepAgentConfig.builder().build(), null);
        conflicting.getAgent().getAbilityManager().add(com.openjiuwen.core.foundation.tool.ToolCard.builder()
                .id(IntentRoutingRail.TOOL_NAME).name(IntentRoutingRail.TOOL_NAME).build());
        assertThatThrownBy(() -> binder.bind(conflicting, suite())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already contains");
    }

    private static IntentSuite suite() {
        return IntentSuite.builder(IntentSuiteConfig.defaults()).initializer(new DefaultIntentInitializer())
                .matcher(context -> Optional.empty()).build();
    }
}
