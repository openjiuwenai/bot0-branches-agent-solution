/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies controller SSE/REST output lines: identification strictly precedes the
 * FEAT-002 generic error mapping (fixed in the processing chain, not configurable).
 * Never uses exception-text keywords or natural-language similarity (spec 2.2/3.5).
 *
 * <p>Identification hit but required field extraction missing (key absent) →
 * IGNORED (WARN observable, line suppressed), not an error: production SSE
 * interleaves incomplete signal frames (e.g. intent echo with {@code text} set
 * and no {@code summary} key); they are controller-internal control noise —
 * neither processed nor forwarded to the end user (spec 2.2, confirmed
 * 2026-08-19). Blank values count as present — non-participating fields
 * legitimately carry "" (e.g. direct target empty, resolved by intent
 * mapping).
 *
 * @since 2026-08-19
 */
public class IntentHandoffClassifier {
    private static final Logger log = LoggerFactory.getLogger(IntentHandoffClassifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ControllerHandoffProperties properties;

    public IntentHandoffClassifier(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    public HandoffClassification classify(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return HandoffClassification.notHandoff();
        }
        ControllerHandoffProperties.Classify classify = properties.getClassify();
        if (classify == null) {
            return HandoffClassification.notHandoff();
        }
        String data = stripSseDataPrefix(rawLine.trim());
        JsonNode json = readTree(data);
        if (!matchesEventType(rawLine.trim(), json, classify.getEventType())) {
            return HandoffClassification.notHandoff();
        }
        String value = readPath(json, classify.getFieldPath());
        if (value == null || classify.getFieldValue() == null || !classify.getFieldValue().contains(value)) {
            return HandoffClassification.notHandoff();
        }

        ControllerHandoffProperties.Fields fields = properties.getFields();
        List<String> missing = new ArrayList<>();
        String handoffType = requiredValue(json, fields.getHandoffType(), "handoff-type", missing);
        String intentId = requiredValue(json, fields.getIntentId(), "intent-id", missing);
        String businessDomain = requiredValue(json, fields.getBusinessDomain(), "business-domain", missing);
        String targetAgentId = requiredValue(json, fields.getTargetAgentId(), "target-agent-id", missing);
        String dedupKey = readPath(json, fields.getDedupKey()); // optional per spec 2.2
        if (!missing.isEmpty()) {
            // 生产 SSE 会混入信号字段不全的 message 帧（如 text 带值、summary 键缺失的
            // 意图回显）：识别命中但提取路径缺失时整行抑制（WARN 可观测）——不处理、
            // 不透传基线、不报错（spec 2.2，2026-08-19 确认）
            log.warn("handoff classify hit but required field(s) missing, ignoring as non-handoff:"
                    + " missing={} line={}", missing, rawLine.trim());
            return new HandoffClassification(HandoffClassification.Outcome.IGNORED, null);
        }
        return new HandoffClassification(HandoffClassification.Outcome.HANDOFF,
                new IntentHandoff(handoffType, intentId, businessDomain, targetAgentId, dedupKey, data));
    }

    private static String requiredValue(JsonNode json, String configuredPath, String label, List<String> missing) {
        // 注意只按"路径缺失"计忽略（键不存在）：空串视为字段在场——非本次解析
        // 来源的字段合法为空（如 direct 目标为空走 intent 映射，spec 3.2）
        String value = readPath(json, configuredPath);
        if (value == null) {
            missing.add(label);
        }
        return value;
    }

    private static boolean matchesEventType(String rawLine, JsonNode json, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return true;
        }
        if (rawLine.startsWith("event:") && eventType.equals(rawLine.substring("event:".length()).trim())) {
            return true;
        }
        return json != null && json.isObject() && json.hasNonNull("event")
                && eventType.equals(json.get("event").asText());
    }

    private static String readPath(JsonNode json, String path) {
        if (json == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode node = json.at(path.startsWith("/") ? path : "/" + path.replace('.', '/'));
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static JsonNode readTree(String data) {
        try {
            return MAPPER.readTree(data);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String stripSseDataPrefix(String trimmed) {
        // 两类入口：data: 前缀行（剥前缀后解析 JSON）；event: 等其他 SSE 字段行返回空串，
        // 此时 readTree 得 null，由 matchesEventType 回头匹配原始行（event-type 显式配置时）
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).trim();
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "";
        }
        return trimmed;
    }
}
