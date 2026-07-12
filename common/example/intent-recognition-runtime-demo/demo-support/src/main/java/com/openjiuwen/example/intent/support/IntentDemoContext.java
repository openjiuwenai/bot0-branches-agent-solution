/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.support;

import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.core.retrieval.reranker.StandardReranker;
import com.openjiuwen.ext.intent.adapter.a2a.A2AAgentCardIntentAdapter;
import com.openjiuwen.ext.intent.adapter.a2a.A2AAgentCardResultEncoder;
import com.openjiuwen.ext.intent.adapter.a2a.A2AEligibilityPolicy;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentRecognizers;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import java.util.List;
import java.util.Set;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;

/** Shared immutable target catalog and intent services for the runtime demos. */
public record IntentDemoContext(List<AgentCard> agentCards, IntentRecognizer<AgentCard> recognizer,
        A2AAgentCardResultEncoder encoder) {

    public IntentDemoContext {
        agentCards = List.copyOf(agentCards);
    }

    public static IntentDemoContext create(IntentDemoProperties properties) {
        properties.requireConfigured();
        return create(properties, new StandardReranker(properties.toRerankerConfig(),
                properties.getReranker().getMaxRetries(), null, null));
    }

    public static IntentDemoContext create(IntentDemoProperties properties, Reranker reranker) {
        List<AgentCard> cards = buildAgentCards();
        A2AAgentCardIntentAdapter adapter = new A2AAgentCardIntentAdapter(new A2AEligibilityPolicy(Set.of("JSONRPC"),
                Set.of("1.0"), Set.of(), Set.of("text/plain"), (card, requirements) -> true, card -> true));
        IntentDemoProperties.Recognition settings = properties.getRecognition();
        IntentRecognizerConfig config = IntentRecognizerConfig.builder().scoreThreshold(settings.getScoreThreshold())
                .marginThreshold(settings.getMarginThreshold()).maxUtteranceLength(settings.getMaxUtteranceLength())
                .maxBatchSize(settings.getMaxBatchSize())
                .maxConcurrentRecognitions(settings.getMaxConcurrentRecognitions()).candidateFormatVersion("a2a-v1")
                .modelVersion(properties.getReranker().getModelName()).build();
        IntentRecognizer<AgentCard> recognizer = IntentRecognizers.<AgentCard> builder().targets(cards)
                .targetAdapter(adapter).reranker(reranker).config(config).build();
        return new IntentDemoContext(cards, recognizer, new A2AAgentCardResultEncoder());
    }

    private static List<AgentCard> buildAgentCards() {
        return List.of(
                card("Order Agent", "Handles order status, logistics tracking, cancellation, and after-sales tasks",
                        "orders",
                        List.of(skill("track-order", "Track order logistics",
                                "Find shipping progress and delivery status for an order",
                                List.of("order", "shipping", "logistics"), List.of("查询订单物流", "where is my order")),
                                skill("cancel-order", "Cancel an order",
                                        "Cancel an order before fulfillment when policy permits",
                                        List.of("order", "cancel"), List.of("取消我的订单")))),
                card("Weather Agent", "Provides current weather and forecasts for cities", "weather",
                        List.of(skill("weather-forecast", "Weather forecast",
                                "Get current conditions and future forecasts for a location",
                                List.of("weather", "forecast"), List.of("明天北京天气如何", "weather in Paris")))),
                card("Knowledge Agent", "Searches internal product and policy knowledge", "knowledge",
                        List.of(skill("knowledge-search", "Knowledge search",
                                "Answer questions from product documentation and policy knowledge",
                                List.of("knowledge", "policy", "documentation"),
                                List.of("查询退款政策", "find the product manual")))));
    }

    private static AgentCard card(String name, String description, String host, List<AgentSkill> skills) {
        return AgentCard.builder().name(name).description(description).version("1.0.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("application/json")).skills(skills)
                .supportedInterfaces(
                        List.of(new AgentInterface("JSONRPC", "https://" + host + ".example/a2a", null, "1.0")))
                .build();
    }

    private static AgentSkill skill(String id, String name, String description, List<String> tags,
            List<String> examples) {
        return new AgentSkill(id, name, description, tags, examples, List.of("text/plain"), List.of("application/json"),
                null);
    }
}
