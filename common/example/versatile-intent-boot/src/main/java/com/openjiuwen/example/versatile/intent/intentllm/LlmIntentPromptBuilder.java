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
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        if (request.getMessages() != null) {
            for (Map<String, Object> m : request.getMessages()) {
                Object role = m.get("role");
                Object content = m.get("content");
                if (role != null && content != null) {
                    messages.add(Map.of("role", role, "content", String.valueOf(content)));
                }
            }
        }
        return messages;
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
                .append("若用户请求属于你的领域，输出：")
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
