/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.deepagent.IntentDeepAgentBinder;
import com.openjiuwen.agents.intent.deepagent.IntentRoutingRail;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.adapters.agentcore.ext.external.A2ADelegateRail;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/** Tests idempotent DeepAgent intent feature installation. */
class IntentDeepAgentInstallerTest {
    @Test
    void installsIntentToolIdempotentlyAndKeepsInternalDelegateHidden() {
        DeepAgent deepAgent = new DeepAgent(null, DeepAgentConfig.builder().build(), null);
        IntentDeepAgentInstaller installer = new IntentDeepAgentInstaller(new IntentDeepAgentBinder(), suite(),
                new A2ARemoteAgentCardRegistry(), false);

        installer.install(deepAgent);
        installer.install(deepAgent);

        assertThat(installer.exposeAgentCardTools()).isFalse();
        assertThat(deepAgent.getAgent().getAbilityManager().listToolInfo()).extracting(tool -> tool.getName())
                .containsExactly(IntentRoutingRail.TOOL_NAME);
        assertThat(deepAgent.getAgent().getAbilityManager().get(A2ADelegateRail.TARGET_NAME)).isNull();
    }

    @Test
    void ignoresNonDeepAgentWithoutChangingCompatibilityFlag() {
        IntentDeepAgentInstaller installer = new IntentDeepAgentInstaller(new IntentDeepAgentBinder(), suite(),
                new A2ARemoteAgentCardRegistry(), true);
        installer.install(new Object());
        installer.install(null);
        assertThat(installer.exposeAgentCardTools()).isTrue();
    }

    private static IntentSuite suite() {
        return IntentSuite.builder(IntentSuiteConfig.defaults()).initializer(new DefaultIntentInitializer())
                .matcher(context -> Optional.empty()).build();
    }
}
