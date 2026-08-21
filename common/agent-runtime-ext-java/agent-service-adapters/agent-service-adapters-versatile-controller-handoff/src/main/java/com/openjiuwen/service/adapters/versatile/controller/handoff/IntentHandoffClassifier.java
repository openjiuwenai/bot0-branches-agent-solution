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

import java.util.List;

/**
 * Classifies controller SSE/REST output lines: identification strictly precedes the
 * FEAT-002 generic error mapping (fixed in the processing chain, not configurable).
 * Never uses exception-text keywords or natural-language similarity (spec 2.2/3.5).
 *
 * <p>Identification hit but no usable resolution source — every source listed in
 * {@code target.resolution-priority} absent or blank — {@code →}
 * IGNORED (WARN observable, line suppressed), not an error: production SSE
 * interleaves incomplete signal frames (e.g. intent echo with {@code text} set
 * and no {@code summary} key) and loose identify conditions may catch plain QA
 * reply frames; they are controller-internal control noise — neither processed
 * nor forwarded to the end user (spec 2.2, confirmed 2026-08-19; aligned with
 * HandoffTargetResolver resolution-priority semantics 2026-08-20: a single
 * non-blank source the resolver would actually use suffices — extraction paths
 * outside {@code resolution-priority}, e.g. a target-agent-id configured on the
 * same path as the classify field, do NOT count, so an intent echo frame can
 * never win the hit over the later complete frame carrying {@code summary}).
 * Handoff types configured in {@code handoff.signal.handoff-types} bypass the
 * gate entirely: upstream signals make no outbound call, so no resolution source
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
        if (isUpstreamSignal(handoffType) || hasUsableResolutionSource(intentId, businessDomain,
                targetAgentId)) {
            return new HandoffClassification(HandoffClassification.Outcome.HANDOFF,
                    new IntentHandoff(handoffType, intentId, businessDomain, targetAgentId, dedupKey, data));
        }
        // resolution-priority 参与解析的来源（intent/domain/direct）全缺失或全空串，
        // 且非 signal 类型：无可解析目标，整行抑制（WARN 可观测）——不处理、不透传
        // 基线、不报错（spec 2.2，2026-08-20 对齐 HandoffTargetResolver 的
        // resolution-priority 语义）
        log.warn("handoff classify hit but no usable resolution source, ignoring as non-handoff:"
                + " missing=all-of [intent-id, business-domain, target-agent-id] line={}", rawLine.trim());
        return new HandoffClassification(HandoffClassification.Outcome.IGNORED, null);
    }

    /**
     * 可用解析来源只统计 {@code target.resolution-priority} 列出的来源，与
     * {@link HandoffTargetResolver} 的实际解析顺序对齐：不参与解析的提取字段（如
     * 部署仅提取不使用、与 classify 同路径的 target-agent-id/business-domain）不算
     * 可用来源——否则生产同节点的回显帧（无 summary，但 node_name 路径已提取出值）
     * 会凭这些惰性来源提前命中，抑制掉随后携带 summary 的完整信号帧（2026-08-20）。
     * 默认 priority 含全部三个来源，行为与旧的任一非空规则一致。
     */
    private boolean hasUsableResolutionSource(String intentId, String businessDomain,
            String targetAgentId) {
        List<String> priority = properties.getTarget().getResolutionPriority();
        return priority.contains("direct") && !isBlank(targetAgentId)
                || priority.contains("intent") && !isBlank(intentId)
                || priority.contains("domain") && !isBlank(businessDomain);
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
