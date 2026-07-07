/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests remote A2A tool installation behavior.
 *
 * @since 2026-06-30
 */
@ExtendWith(OutputCaptureExtension.class)
class RemoteA2aToolInstallerTest {
    @Tag("smoke")
    @Test
    void installsRailToolCardIntoBaseAgent(CapturedOutput output) {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);
        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.getDescription()).contains("remote A2A agent 'agent-b'");
                    assertThat(tool.getParameters()).containsEntry("type", "object");
                });
        assertThat(agent.getAbilityManager().listToolInfo())
                .noneMatch(tool -> "agent-b-2".equals(tool.getName()));
        assertThat(output).contains("Installed remote A2A interrupt rail")
                .contains("tools=[agent-b]")
                .contains("rail=RemoteA2aInterruptRail");
    }

    @Test
    void skipsStringAgentIdMode(CapturedOutput output) {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install("agent-id");

        assertThat(output).contains("agent-id mode cannot install remote A2A tools in v1");
    }

    @Test
    void installsRailToolCardIntoDeepAgentInternalAbilityManager() {
        DeepAgent deepAgent = new DeepAgent(AgentCard.builder()
                .id("deep-agent-a")
                .name("Deep Agent A")
                .description("Deep Agent A")
                .build(), DeepAgentConfig.builder().enableTaskLoop(false).build(), null);
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(deepAgent);

        assertThat(deepAgent.getAgent().getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> assertThat(tool.getDescription()).contains("remote A2A agent 'agent-b'"));
    }

    @Test
    void usesRemoteSkillDescriptionsForInjectedToolDescription() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", remoteCard("Remote card fallback description", List.of(
                skill("Process account balance queries"),
                skill("Handle transfer workflow requests"))));
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> assertThat(tool.getDescription())
                        .contains("Process account balance queries")
                        .contains("Handle transfer workflow requests")
                        .doesNotContain("Remote card fallback description"));
    }

    @Test
    void fallsBackToRemoteCardDescriptionWhenSkillsAreEmpty() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", remoteCard("Remote card fallback description", List.of()));
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> assertThat(tool.getDescription())
                        .isEqualTo("Remote card fallback description"));
    }

    @Test
    void keepsRegistryEntryNameAsToolNameWhenRemoteCardNameDiffers() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", remoteCard("Remote card fallback description", List.of(skill("Remote skill"))));
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.getName()).isEqualTo("agent-b");
                    assertThat(tool.getDescription()).isEqualTo("Remote skill");
                });
        assertThat(agent.getAbilityManager().listToolInfo())
                .noneMatch(tool -> "Remote Agent B".equals(tool.getName()));
    }

    @Test
    void preservesRegistryEntryNameAsToolNameWithoutFormatValidation() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("Agent B", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "Agent B".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> assertThat(tool.getDescription()).contains("remote A2A agent 'Agent B'"));
    }

    @Test
    void preservesRegistryEntryNameAsToolNameWithoutTrimming() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register(" agent-b ", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> " agent-b ".equals(tool.getName()))
                .singleElement()
                .satisfies(tool -> assertThat(tool.getDescription())
                        .contains("remote A2A agent ' agent-b '"));
        assertThat(agent.getAbilityManager().listToolInfo())
                .noneMatch(tool -> "agent-b".equals(tool.getName()));
    }

    @Test
    void installsNewRegistryEntriesIncrementally() {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);

        installer.install(agent);
        registry.register("agent-c", null);
        installer.install(agent);

        assertThat(agent.getAbilityManager().listToolInfo())
                .extracting(tool -> tool.getName())
                .contains("agent-b", "agent-c")
                .doesNotContain("agent-b-2", "agent-c-2");
    }

    @Test
    void concurrentInstallDoesNotDuplicateRemoteTool() throws Exception {
        ReActAgent agent = reactAgent();
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("agent-b", null);
        RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(registry);
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        List<CompletableFuture<Void>> tasks = IntStream.range(0, workers)
                .mapToObj(index -> CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                        installer.install(agent);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    } finally {
                        done.countDown();
                    }
                }))
                .toList();

        start.countDown();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(agent.getAbilityManager().listToolInfo())
                .filteredOn(tool -> "agent-b".equals(tool.getName()))
                .singleElement();
    }

    private static ReActAgent reactAgent() {
        return new ReActAgent(AgentCard.builder()
                .id("agent-a")
                .name("Agent A")
                .description("Agent A")
                .build());
    }

    private static org.a2aproject.sdk.spec.AgentCard remoteCard(String description,
            List<org.a2aproject.sdk.spec.AgentSkill> skills) {
        return org.a2aproject.sdk.spec.AgentCard.builder()
                .name("Remote Agent B")
                .description(description)
                .version("1.0")
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of())
                .defaultOutputModes(List.of())
                .skills(skills)
                .securitySchemes(Map.of())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new org.a2aproject.sdk.spec.AgentInterface("jsonrpc",
                        "http://remote/a2a/", null, "1.0")))
                .signatures(List.of())
                .additionalInterfaces(List.of())
                .build();
    }

    private static org.a2aproject.sdk.spec.AgentSkill skill(String description) {
        return new org.a2aproject.sdk.spec.AgentSkill("skill-id", "Skill", description, List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
