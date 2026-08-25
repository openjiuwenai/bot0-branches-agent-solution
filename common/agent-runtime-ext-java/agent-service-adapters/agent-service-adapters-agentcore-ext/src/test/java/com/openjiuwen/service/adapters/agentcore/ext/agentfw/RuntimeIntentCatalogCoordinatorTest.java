/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.RuntimeConfiguredIntents;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogSnapshot;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/** Tests full catalog replacement across Runtime intent sources. */
class RuntimeIntentCatalogCoordinatorTest {
    @Test
    void initializesFromCompleteRegistryAndConfiguredSources() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("transfer-agent", card("transfer"));
        CustomIntentRegistration local = custom("calculator");
        CustomIntentRegistration fallback = custom("fallback");
        IntentSuite suite = suite();
        RuntimeIntentCatalogCoordinator coordinator = new RuntimeIntentCatalogCoordinator(suite, registry,
                new RuntimeConfiguredIntents(List.of(local), fallback));

        coordinator.afterPropertiesSet();

        assertThat(coordinator.remoteCatalogVersion()).isEqualTo(1L);
        assertThat(suite.snapshot().version()).isEqualTo(1L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("a2a:transfer-agent:transfer", "calculator");
        assertThat(suite.snapshot().initializedIntents().fallback().id()).isEqualTo("fallback");
    }

    @Test
    void failedRemoteRefreshKeepsAcceptedVersionAndAllowsSameVersionRetry() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        IntentSuite suite = suite();
        RuntimeIntentCatalogCoordinator coordinator = new RuntimeIntentCatalogCoordinator(suite, registry,
                new RuntimeConfiguredIntents(List.of(custom("local")), null));
        coordinator.afterPropertiesSet();
        assertThat(suite.snapshot().version()).isEqualTo(1L);

        RemoteAgentEntry invalid = new RemoteAgentEntry("broken", null, 30, false);
        coordinator.onRemoteAgentCatalogChanged(
                new RemoteAgentCatalogChangedEvent(new RemoteAgentCatalogSnapshot(3L, List.of(invalid))));
        assertThat(coordinator.remoteCatalogVersion()).isZero();
        assertThat(suite.snapshot().version()).isEqualTo(1L);

        RemoteAgentEntry valid = new RemoteAgentEntry("balance-agent", card("balance"), 30, false);
        coordinator.onRemoteAgentCatalogChanged(
                new RemoteAgentCatalogChangedEvent(new RemoteAgentCatalogSnapshot(3L, List.of(valid))));
        assertThat(coordinator.remoteCatalogVersion()).isEqualTo(3L);
        assertThat(suite.snapshot().version()).isEqualTo(2L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("a2a:balance-agent:balance", "local");

        coordinator.onRemoteAgentCatalogChanged(
                new RemoteAgentCatalogChangedEvent(new RemoteAgentCatalogSnapshot(2L, List.of())));
        assertThat(coordinator.remoteCatalogVersion()).isEqualTo(3L);
        assertThat(suite.snapshot().version()).isEqualTo(2L);
    }

    @Test
    void customReplacementIsFullAndFailureRemainsVisibleToCaller() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        IntentSuite suite = suite();
        RuntimeIntentCatalogCoordinator coordinator = new RuntimeIntentCatalogCoordinator(suite, registry,
                new RuntimeConfiguredIntents(List.of(custom("first")), null));
        coordinator.afterPropertiesSet();

        coordinator.replaceCustomIntents(List.of(custom("second")), custom("fallback"));
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("second");
        assertThat(suite.snapshot().initializedIntents().fallback().id()).isEqualTo("fallback");

        assertThatThrownBy(() -> coordinator.replaceCustomIntents(List.of(custom("same"), custom("same")), null))
                .isInstanceOf(RuntimeException.class);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("second");
    }

    @Test
    void replacingAllSourcesWithEmptyCatalogRemovesPreviousIntents() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        IntentSuite suite = suite();
        RuntimeIntentCatalogCoordinator coordinator = new RuntimeIntentCatalogCoordinator(suite, registry,
                new RuntimeConfiguredIntents(List.of(custom("first")), custom("fallback")));
        coordinator.afterPropertiesSet();

        coordinator.replaceCustomIntents(List.of(), null);

        assertThat(suite.snapshot().version()).isEqualTo(2L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).isEmpty();
        assertThat(suite.snapshot().initializedIntents().fallback()).isNull();
    }

    private static IntentSuite suite() {
        return IntentSuite.builder(IntentSuiteConfig.defaults()).initializer(new DefaultIntentInitializer())
                .matcher(context -> Optional.empty()).build();
    }

    private static CustomIntentRegistration custom(String id) {
        return new CustomIntentRegistration(id, id + " description", context -> new ReturnAction(id));
    }

    private static AgentCard card(String skillId) {
        AgentSkill skill = new AgentSkill(skillId, skillId, skillId + " operations", List.of(), List.of(),
                List.of("text/plain"), List.of("text/plain"), List.of());
        return AgentCard.builder().name(skillId + " agent").description(skillId + " agent").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain")).skills(List.of(skill))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://localhost/a2a", null, "1.0")))
                .build();
    }
}
