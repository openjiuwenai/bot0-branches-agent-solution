/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.deepagents.DeepAgentsFactory;
import com.openjiuwen.ext.intent.adapter.a2a.A2AAgentCardIntentAdapter;
import com.openjiuwen.ext.intent.adapter.a2a.A2AAgentCardResultEncoder;
import com.openjiuwen.ext.intent.adapter.a2a.A2AEligibilityPolicy;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentRecognizers;
import com.openjiuwen.ext.intent.api.IntentResultEncoders;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import com.openjiuwen.ext.intent.tool.IntentRecognitionTool;
import com.openjiuwen.ext.intent.tool.IntentRecognitionToolConfig;
import com.openjiuwen.ext.intent.workflow.IntentRecognitionComponent;
import com.openjiuwen.ext.intent.workflow.IntentRecognitionExecutable;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

class IntentFrameworkIntegrationTest {
    @Test
    void sharesOneA2ARecognizerBetweenToolAndWorkflowAndMountsComponent() {
        AgentCard order = card("Order Agent", "orders", "track", "Track an order");
        AgentCard weather = card("Weather Agent", "weather", "forecast", "Get a weather forecast");
        A2AAgentCardIntentAdapter adapter = adapter();
        String orderCandidateId = adapter.targetKey(order) + ":track";
        String weatherCandidateId = adapter.targetKey(weather) + ":forecast";
        IntentRecognizer<AgentCard> recognizer = IntentRecognizers.<AgentCard>builder().targets(List.of(order, weather))
                .targetAdapter(adapter).reranker(reranker(Map.of(orderCandidateId, 0.94, weatherCandidateId, 0.20)))
                .config(config()).build();
        A2AAgentCardResultEncoder encoder = new A2AAgentCardResultEncoder();
        IntentRecognitionTool<AgentCard> tool = new IntentRecognitionTool<>(recognizer, encoder,
                new IntentRecognitionToolConfig("intent-integration-" + UUID.randomUUID(), null));
        IntentRecognitionComponent<AgentCard> component = new IntentRecognitionComponent<>(recognizer, encoder);
        Workflow workflow = new Workflow();

        workflow.addWorkflowComp("intent", component, Map.of("utterance", "${start.query}"));
        JsonNode toolResult = (JsonNode) tool.invoke(Map.of("utterance", "where is my order"), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> workflowResult = (Map<String, Object>) ((IntentRecognitionExecutable<AgentCard>) component
                .toExecutable()).invoke(Map.of("utterance", "where is my order"), null, null);

        assertThat(toolResult.path("matched").asBoolean()).isTrue();
        assertThat(toolResult.path("target").path("name").asText()).isEqualTo("Order Agent");
        assertThat(workflowResult).isEqualTo(IntentResultEncoders.toMap(toolResult));
        assertThat(workflow).isNotNull();
    }

    @Test
    void deepAgentsUseSameNameWithDistinctResourceIds() {
        String firstId = "intent-deep-first-" + UUID.randomUUID();
        String secondId = "intent-deep-second-" + UUID.randomUUID();
        IntentRecognitionTool<AgentCard> first = tool(firstId, card("Order Agent", "orders", "track", "Track"));
        IntentRecognitionTool<AgentCard> second = tool(secondId,
                card("Weather Agent", "weather", "forecast", "Forecast"));
        try {
            DeepAgent firstAgent = new DeepAgentsFactory()
                    .createDeepAgent(DeepAgentConfig.builder().tools(List.of(first)).build());
            DeepAgent secondAgent = new DeepAgentsFactory()
                    .createDeepAgent(DeepAgentConfig.builder().tools(List.of(second)).build());
            firstAgent.ensureInitialized();
            secondAgent.ensureInitialized();

            assertThat(first.getCard().getName()).isEqualTo("intent_recognition");
            assertThat(second.getCard().getName()).isEqualTo("intent_recognition");
            assertThat(Runner.resourceMgr().getTool(firstId)).isSameAs(first);
            assertThat(Runner.resourceMgr().getTool(secondId)).isSameAs(second);
        } finally {
            Runner.resourceMgr().removeTool(firstId, null, TagMatchStrategy.ALL, true);
            Runner.resourceMgr().removeTool(secondId, null, TagMatchStrategy.ALL, true);
        }
    }

    private static IntentRecognitionTool<AgentCard> tool(String id, AgentCard card) {
        A2AAgentCardIntentAdapter adapter = adapter();
        String candidateId = adapter.targetKey(card) + ":" + card.skills().get(0).id();
        IntentRecognizer<AgentCard> recognizer = IntentRecognizers.<AgentCard>builder().targets(List.of(card))
                .targetAdapter(adapter).reranker(reranker(Map.of(candidateId, 0.9))).config(config()).build();
        return new IntentRecognitionTool<>(recognizer, new A2AAgentCardResultEncoder(),
                new IntentRecognitionToolConfig(id, null));
    }

    private static A2AAgentCardIntentAdapter adapter() {
        return new A2AAgentCardIntentAdapter(
                new A2AEligibilityPolicy(java.util.Set.of("JSONRPC"), java.util.Set.of("1.0"), java.util.Set.of(),
                        java.util.Set.of("text/plain"), (card, requirements) -> true, card -> true));
    }

    private static AgentCard card(String name, String description, String skillId, String skillDescription) {
        return AgentCard.builder().name(name).description(description).version("1.0.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("application/json"))
                .skills(List.of(
                        new AgentSkill(skillId, skillDescription, skillDescription, List.of(), null, null, null, null)))
                .supportedInterfaces(
                        List.of(new AgentInterface("JSONRPC", "https://" + skillId + ".example/a2a", null, "1.0")))
                .build();
    }

    private static Reranker reranker(Map<String, Double> configuredScores) {
        return new Reranker() {
            @Override
            public Map<String, Double> rerankScores(String query, List<?> documents, Object instruct,
                    Map<String, Object> options) {
                Map<String, Double> batch = new LinkedHashMap<>();
                for (Object document : documents) {
                    String id = ((RetrievalResult) document).getChunkId();
                    batch.put(id, configuredScores.get(id));
                }
                return batch;
            }

            @Override
            public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static IntentRecognizerConfig config() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("a2a-v1").modelVersion("fake-model-v1").build();
    }
}
