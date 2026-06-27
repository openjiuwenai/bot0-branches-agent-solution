package com.openjiuwen.example.agentcoreext.agent_a;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.SkillUseRail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeepAgentRuntimeApplicationTest {

    @Test
    void buildsDeepAgentWithSkillUseRail() {
        DeepAgentLlmProperties properties = new DeepAgentLlmProperties();
        properties.setApiKey("test-key");
        properties.setSkillDirectories(List.of("skills"));

        DeepAgent agent = DeepAgentRuntimeApplication.buildDeepAgent(properties);

        assertThat(agent.getConfig().getSkillDirectories()).containsExactly("skills");
        assertThat(agent.getConfig().getSkillMode()).isEqualTo("all");
        assertThat(agent.getConfig().isEnableTaskLoop()).isTrue();
        assertThat(agent.getConfig().getRails()).hasAtLeastOneElementOfType(SkillUseRail.class);
    }
}
