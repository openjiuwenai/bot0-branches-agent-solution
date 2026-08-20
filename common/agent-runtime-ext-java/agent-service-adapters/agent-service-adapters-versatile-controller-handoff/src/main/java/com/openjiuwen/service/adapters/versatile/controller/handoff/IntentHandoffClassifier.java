/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies controller SSE/REST output lines: identification strictly precedes the
 * FEAT-002 generic error mapping (fixed in the processing chain, not configurable).
 * Never uses exception-text keywords or natural-language similarity (spec 2.2/3.5).
 *
 * <p>Identification hit but no usable resolution source — all three of
 * intent-id / business-domain / target-agent-id absent or blank — {@code →}
 * IGNORED (WARN observable, line suppressed), not an error: production SSE
 * interleaves incomplete signal frames (e.g. intent echo with {@code text} set
 * and no {@code summary} key) and loose identify conditions may catch plain QA
 * reply frames; they are controller-internal control noise — neither processed
 * nor forwarded to the end user (spec 2.2, confirmed 2026-08-19; relaxed to
 * any-one-source 2026-08-20, aligning with HandoffTargetResolver
 * resolution-priority semantics). A single non-blank source suffices —
 * production frames carry only the field the current hop resolves by. Handoff
 * types configured in {@code handoff.signal.handoff-types} bypass the gate
 * entirely: upstream signals make no outbound call, so no resolution source
 * is required (spec 3.4). handoff-type is otherwise optional (nullable):
 * signal handoff-types matching simply does not hit when absent.
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
        String handoffType = readPath(json, fields.getHandoffType()); // optional: signal 路由未命中即走解析链
        String intentId = readPath(json, fields.getIntentId());
        String businessDomain = readPath(json, fields.getBusinessDomain());
        String targetAgentId = readPath(json, fields.getTargetAgentId());
        String dedupKey = readPath(json, fields.getDedupKey()); // optional per spec 2.2
        if (isUpstreamSignal(handoffType) || !(isBlank(intentId) && isBlank(businessDomain)
                && isBlank(targetAgentId))) {
            return new HandoffClassification(HandoffClassification.Outcome.HANDOFF,
                    new IntentHandoff(handoffType, intentId, businessDomain, targetAgentId, dedupKey, data));
        }
        // 三个解析来源（intent/domain/direct）全缺失或全空串，且非 signal 类型：无可解析
        // 目标，整行抑制（WARN 可观测）——不处理、不透传基线、不报错（spec 2.2，
        // 2026-08-20 放宽为任一来源非空即可，对齐 HandoffTargetResolver 的 resolution-priority）
        log.warn("handoff classify hit but no usable resolution source, ignoring as non-handoff:"
                + " missing=all-of [intent-id, business-domain, target-agent-id] line={}", rawLine.trim());
        return new HandoffClassification(HandoffClassification.Outcome.IGNORED, null);
    }

    private boolean isUpstreamSignal(String handoffType) {
        // signal.handoff-types 命中的类型不出站（upstream-signal，spec 3.4），无需解析目标
        return handoffType != null && properties.getSignal().getHandoffTypes().contains(handoffType);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
