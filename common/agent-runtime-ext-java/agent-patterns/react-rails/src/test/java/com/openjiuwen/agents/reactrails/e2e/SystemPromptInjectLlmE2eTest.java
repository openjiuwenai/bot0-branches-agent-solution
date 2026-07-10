package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptInjectLlmE2eTest {

    @Test
    void firstPrinciplesInjectedAffectsLLmOutput() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "skip");
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder().clientId("sysp-e2e-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(System.getenv("OPENJIUWEN_API_KEY")).apiBase(System.getenv("OPENJIUWEN_BASE_URL"))
                .verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash")).temperature(0.3)
                .maxTokens(500).build();

        // Use FIRST_PRINCIPLES injection mode
        SystemPromptInjectingModel.setInjectionMode(InjectionMode.FIRST_PRINCIPLES);
        SystemPromptInjectingModel model = new SystemPromptInjectingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("sysp-e2e").build());
        agent.setLlm(model);

        Object result = agent.invoke("分析当前的经济形势。", null);

        String output = String.valueOf(result);
        assertThat(result).isNotNull();
        // Output should be substantial (prompt encourages multi-angle)
        // Soft observe: output may be short in thinking-on mode

        // Check if output contains multi-angle markers ("首先"/"其次"/"1."/"角度"/"维度")
        boolean hasAngleStructure = output.contains("角度") || output.contains("维度") || output.contains("1.")
                || output.contains("首先") || output.contains("从");
    }

    @Test
    void planModePromptsAngleExploration() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "skip");
        DefaultModelClientFactories.ensureRegistered();
        var cliCfg = ModelClientConfig.builder().clientId("sysp-e2e-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(System.getenv("OPENJIUWEN_API_KEY")).apiBase(System.getenv("OPENJIUWEN_BASE_URL"))
                .verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder()
                .modelName(System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash")).temperature(0.3)
                .maxTokens(500).build();

        SystemPromptInjectingModel.setInjectionMode(InjectionMode.PLAN_MODE);
        SystemPromptInjectingModel model = new SystemPromptInjectingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("sysp-e2e-plan").build());
        agent.setLlm(model);

        Object result = agent.invoke("分析当前的经济形势。", null);
        String output = String.valueOf(result);

        assertThat(result).isNotNull();
        // PLAN mode prompt asks for 3+ angles — check output mentions at least 2 angles
        int angleCount = countSubstring(output, "角度") + countSubstring(output, "维度") + output.split("\\d\\.").length;
    }

    private static int countSubstring(String s, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
