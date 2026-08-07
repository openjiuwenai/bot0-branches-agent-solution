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
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.intent.IntentDeepAgentInstaller;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

class JiuwenCoreAgentExtHandlerIntentTest {
    @Test
    void keepsRemoteAgentToolsWhenIntentRoutingIsDisabled() throws Exception {
        Fixture fixture = fixture();
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(fixture.deepAgent());
        handler.setRemoteA2aToolInstaller(fixture.remoteInstaller());

        installBeforeRun(handler);

        assertToolNames(fixture.deepAgent(), "transfer-agent");
    }

    @Test
    void hidesPerAgentToolsWhenIntentRoutingUsesUnifiedDelegateOnly() throws Exception {
        Fixture fixture = fixture();
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(fixture.deepAgent());
        handler.setRemoteA2aToolInstaller(fixture.remoteInstaller());
        handler.setIntentDeepAgentInstaller(fixture.intentInstaller(false));

        installBeforeRun(handler);

        assertToolNames(fixture.deepAgent(), IntentRoutingRail.TOOL_NAME);
    }

    @Test
    void exposesPerAgentToolsAlongsideIntentRoutingWhenCompatibilityFlagIsEnabled() throws Exception {
        Fixture fixture = fixture();
        JiuwenCoreAgentExtHandler handler = new JiuwenCoreAgentExtHandler(fixture.deepAgent());
        handler.setRemoteA2aToolInstaller(fixture.remoteInstaller());
        handler.setIntentDeepAgentInstaller(fixture.intentInstaller(true));

        installBeforeRun(handler);

        assertToolNames(fixture.deepAgent(), IntentRoutingRail.TOOL_NAME, "transfer-agent");
    }

    private static Fixture fixture() {
        DeepAgent deepAgent = new DeepAgent(null, DeepAgentConfig.builder().build(), null);
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("transfer-agent", remoteCard());
        return new Fixture(deepAgent, registry);
    }

    private static AgentCard remoteCard() {
        AgentSkill skill = new AgentSkill("transfer", "Transfer", "Transfer funds", List.of(), List.of(),
                List.of("text/plain"), List.of("text/plain"), List.of());
        return AgentCard.builder().name("transfer-agent").description("Transfer agent").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://localhost/a2a", null, "1.0")))
                .skills(List.of(skill)).build();
    }

    private static void installBeforeRun(JiuwenCoreAgentExtHandler handler) throws Exception {
        Method method = JiuwenCoreAgentExtHandler.class.getDeclaredMethod("installBeforeRun");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private static void assertToolNames(DeepAgent deepAgent, String... names) {
        assertThat(deepAgent.getAgent().getAbilityManager().listToolInfo()).extracting(tool -> tool.getName())
                .containsExactlyInAnyOrder(names);
    }

    private record Fixture(DeepAgent deepAgent, A2ARemoteAgentCardRegistry registry) {
        private RemoteA2aToolInstaller remoteInstaller() {
            return RemoteA2aToolInstaller.create(registry);
        }

        private IntentDeepAgentInstaller intentInstaller(boolean exposeAgentCardTools) {
            IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                    .initializer(new DefaultIntentInitializer()).matcher(context -> Optional.empty()).build();
            return new IntentDeepAgentInstaller(new IntentDeepAgentBinder(), suite, registry, exposeAgentCardTools);
        }
    }
}
