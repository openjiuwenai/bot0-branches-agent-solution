/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 执行树节点/边记录模型（落盘 JSON 形态）。节点 key 为 {@code runtime:run:{runId}}，
 * 边 key 为 {@code runtime:run-edge:{parentRunId}:{runId}}，字段与 L2 §5.1 对齐。
 *
 * @since 2026-08-26
 */
public final class RunTreeRecord {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunTreeRecord() {
    }

    /**
     * Builds the node-open record JSON.
     *
     * @param runId    run id ({taskId}#{roundSeq})
     * @param kind     node kind (local / remote)
     * @param startedAt ISO-8601 start time with zone
     * @param traceId  trace id, may be null
     * @param tenantId tenant id, may be null
     * @param parentRunId parent run id from ingress metadata, may be null
     * @return node JSON
     */
    public static String nodeOpen(String runId, String kind, String startedAt, String traceId, String tenantId,
                                  String parentRunId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("runId", runId);
        node.put("kind", kind);
        node.put("startedAt", startedAt);
        putIfPresent(node, "traceId", traceId);
        putIfPresent(node, "tenantId", tenantId);
        putIfPresent(node, "parentRunId", parentRunId);
        return toJson(node);
    }

    /**
     * Builds the node-close record JSON (completes the open record's fields).
     *
     * @param runId      run id
     * @param kind       node kind
     * @param startedAt  ISO-8601 start time
     * @param endedAt    ISO-8601 end time
     * @param finalState terminal state name
     * @param traceId    trace id, may be null
     * @param tenantId   tenant id, may be null
     * @param parentRunId parent run id from ingress metadata, may be null
     * @return node JSON
     */
    public static String nodeClose(String runId, String kind, String startedAt, String endedAt,
                                   String finalState, String traceId, String tenantId, String parentRunId) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("runId", runId);
        node.put("kind", kind);
        node.put("startedAt", startedAt);
        node.put("endedAt", endedAt);
        node.put("finalState", finalState);
        putIfPresent(node, "traceId", traceId);
        putIfPresent(node, "tenantId", tenantId);
        putIfPresent(node, "parentRunId", parentRunId);
        return toJson(node);
    }

    /**
     * Builds the delegation edge record JSON.
     *
     * @param parentRunId    parent run id (full form {taskId}#{roundSeq})
     * @param runId          child (remote) run id
     * @param toolCallId     tool call id that triggered the delegation
     * @param agentName      remote agent name
     * @param state          member state
     * @param resultCategory result category, may be null
     * @return edge JSON
     */
    public static String edge(String parentRunId, String runId, String toolCallId,
                              String agentName, String state, String resultCategory) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("parentRunId", parentRunId);
        edge.put("runId", runId);
        edge.put("toolCallId", toolCallId);
        edge.put("agentName", agentName);
        edge.put("state", state);
        putIfPresent(edge, "resultCategory", resultCategory);
        return toJson(edge);
    }

    /**
     * Reads the traceId field from a node record JSON.
     *
     * @param nodeJson node record JSON
     * @return trace id, or empty
     */
    public static Optional<String> readTraceId(String nodeJson) {
        return readNode(nodeJson).flatMap(NodeView::traceId);
    }

    /**
     * Reads the recoverable fields of a node record JSON (restart recovery reads these
     * back so closes do not wipe them).
     *
     * @param nodeJson node record JSON
     * @return node field view, or empty when unparseable
     */
    public static Optional<NodeView> readNode(String nodeJson) {
        try {
            JsonNode node = MAPPER.readTree(nodeJson);
            return Optional.of(new NodeView(text(node.get("traceId")), text(node.get("tenantId")),
                    text(node.get("parentRunId")), text(node.get("startedAt")), text(node.get("finalState"))));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(JsonNode node) {
        return node != null && node.isTextual() ? Optional.of(node.asText()) : Optional.empty();
    }

    /**
     * Recoverable fields of a node record.
     *
     * @param traceId     trace id
     * @param tenantId    tenant id
     * @param parentRunId parent run id
     * @param startedAt   round start time
     * @param finalState  terminal state when the node is already closed
     */
    public record NodeView(Optional<String> traceId, Optional<String> tenantId,
                           Optional<String> parentRunId, Optional<String> startedAt,
                           Optional<String> finalState) {
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
