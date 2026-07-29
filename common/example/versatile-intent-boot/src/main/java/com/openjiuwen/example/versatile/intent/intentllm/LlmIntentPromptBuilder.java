/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the OpenAI-compatible chat messages for an intent-classification call.
 *
 * @since 0.1.0
 */
class LlmIntentPromptBuilder {
    private final VersatileProperties versatile;
    private final LlmIntentProperties properties;

    LlmIntentPromptBuilder(VersatileProperties versatile, LlmIntentProperties properties) {
        this.versatile = versatile;
        this.properties = properties;
    }

    List<Map<String, Object>> build(ServeRequest request) {
        return buildConversation(extractTurns(request));
    }

    /**
     * Builds chat messages (system + conversation turns) from an explicit turn
     * list. The {@link LlmIntentAgentHandler} maintains per-conversation user
     * history and passes it here so the LLM sees prior inputs when classifying a
     * follow-up message (e.g. {@code "500元"} as a hotel-budget answer).
     *
     * @param turns ordered conversation turns (role/content maps); may be empty
     * @return system message followed by the supplied turns
     */
    List<Map<String, Object>> buildConversation(List<Map<String, Object>> turns) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        if (turns != null) {
            for (Map<String, Object> m : turns) {
                Object role = m.get("role");
                Object content = m.get("content");
                if (role != null && content != null) {
                    messages.add(Map.of("role", role, "content", String.valueOf(content)));
                }
            }
        }
        return messages;
    }

    private List<Map<String, Object>> extractTurns(ServeRequest request) {
        List<Map<String, Object>> turns = new ArrayList<>();
        if (request.getMessages() != null) {
            for (Map<String, Object> m : request.getMessages()) {
                Object role = m.get("role");
                Object content = m.get("content");
                if (role != null && content != null) {
                    turns.add(Map.of("role", role, "content", String.valueOf(content)));
                }
            }
        }
        return turns;
    }

    private String systemPrompt() {
        String domain = properties.getDomain();
        String domainText = (domain == null || domain.isBlank())
                ? "全领域（酒店与机票）" : domain + " 领域";
        String ambiguousId = versatile.getAmbiguousIntentId() == null
                ? "1" : versatile.getAmbiguousIntentId();
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个意图分类器，负责").append(domainText).append("的意图识别。")
                .append("可选目标 agent（agent_id）：").append(availableAgentCards()).append("。")
                .append("仅输出 JSON，不要输出任何其他文字。")
                .append("请根据对话中【最后一条用户消息】判断当前意图。");
        if (domain == null || domain.isBlank()) {
            sb.append("用户可能中途切换话题（例如先订酒店再买机票）；无论之前讨论什么，都按最后一条消息的意图分类，")
                    .append("不要因为话题切换或前文上下文而输出 ambiguous。");
        }
        sb.append("若用户请求属于你的领域，输出：")
                .append("{\"action\":\"classify\",\"intent_id\":\"<意图id>\",")
                .append("\"agent_id\":\"<可选目标agent之一>\",\"response_content\":\"<简短中文描述>\"}。")
                .append("若不属于你的领域（无法处理），输出：")
                .append("{\"action\":\"ambiguous\",\"intent_id\":\"").append(ambiguousId)
                .append("\",\"response_content\":\"<为何不属于>\"}。");
        return sb.toString();
    }

    private String availableAgentCards() {
        Set<String> cards = new LinkedHashSet<>();
        if (versatile.getIntentAgentMapping() != null) {
            versatile.getIntentAgentMapping().values().forEach(list ->
                    list.forEach(c -> {
                        if (c.getAgentCard() != null && !c.getAgentCard().isBlank()) {
                            cards.add(c.getAgentCard());
                        }
                    }));
        }
        return String.join(", ", cards);
    }
}
