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
import java.util.Optional;

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

    /**
     * 分类控制器单行输出。
     *
     * @param rawLine 控制器原始输出行（SSE data/event 行或 REST 行，可为 {@code null}）
     * @return 分类结果：HANDOFF / NOT_HANDOFF / IGNORED（识别命中但无可用解析来源）
     */
    public HandoffClassification classify(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return HandoffClassification.notHandoff();
        }
        ControllerHandoffProperties.Classify classify = properties.getClassify();
        if (classify == null) {
            return HandoffClassification.notHandoff();
        }
        String data = stripSseDataPrefix(rawLine.trim());
        Optional<JsonNode> json = readTree(data);
        if (!matchesEventType(rawLine.trim(), json, classify.getEventType())) {
            return HandoffClassification.notHandoff();
        }
        Optional<String> value = readPath(json, classify.getFieldPath());
        if (value.isEmpty() || classify.getFieldValue() == null
                || !classify.getFieldValue().contains(value.get())) {
            return HandoffClassification.notHandoff();
        }

        ControllerHandoffProperties.Fields fields = properties.getFields();
        // optional: signal 路由未命中即走解析链
        String handoffType = readPath(json, fields.getHandoffType()).orElse(null);
        String intentId = readPath(json, fields.getIntentId()).orElse(null);
        String businessDomain = readPath(json, fields.getBusinessDomain()).orElse(null);
        String targetAgentId = readPath(json, fields.getTargetAgentId()).orElse(null);
        String dedupKey = readPath(json, fields.getDedupKey()).orElse(null); // optional per spec 2.2
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
     *
     * @param intentId 提取的意图标识（可缺失）
     * @param businessDomain 提取的业务域（可缺失）
     * @param targetAgentId 提取的直连目标（可缺失）
     * @return resolution-priority 参与来源中任一非空即 {@code true}
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

    private static boolean matchesEventType(String rawLine, Optional<JsonNode> json, String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return true;
        }
        if (rawLine.startsWith("event:") && eventType.equals(rawLine.substring("event:".length()).trim())) {
            return true;
        }
        return json.isPresent() && json.get().isObject() && json.get().hasNonNull("event")
                && eventType.equals(json.get().get("event").asText());
    }

    private static Optional<String> readPath(Optional<JsonNode> json, String path) {
        if (json.isEmpty() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        JsonNode node = json.get().at(path.startsWith("/") ? path : "/" + path.replace('.', '/'));
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node.isTextual() ? node.asText() : node.toString());
    }

    private static Optional<JsonNode> readTree(String data) {
        try {
            return Optional.of(MAPPER.readTree(data));
        } catch (JsonProcessingException ignored) {
            return Optional.empty();
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
