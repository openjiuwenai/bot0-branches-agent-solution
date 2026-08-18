/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.validate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * G3 — basic parameter validation (FEAT-011 L2 §3.5). Parses the JSON-RPC body
 * and validates shape/method without querying RDC ("目标可不可路由" belongs to
 * routing). Classifies create vs resume by the presence of a non-empty
 * {@code params.message.taskId}:
 * <ul>
 *   <li>create ({@code SendMessage}/{@code SendStreamingMessage}, no taskId):
 *       {@code params.metadata.agentId} may be absent (default Agent later) but
 *       an empty-string value is rejected with {@code VALIDATION_AGENT_ID}.</li>
 *   <li>resume (non-empty taskId): {@code taskId} captured; {@code agentId} not
 *       required (not used for routing).</li>
 * </ul>
 * Field read paths (L2 §3.5.1): {@code params.metadata.agentId},
 * {@code params.message.taskId}, {@code params.message.messageId},
 * {@code params.message.contextId}. 730 method whitelist: {@code SendMessage},
 * {@code SendStreamingMessage}.
 *
 * <p>Uses a private ObjectMapper: the gateway only parses opaque bodies (no
 * Java 8 value types), so the default mapper suffices, and this avoids depending
 * on an injected ObjectMapper bean (Boot 4 does not expose one by default).
 *
 * @since 0.1.0
 */
@Component
public class ParamValidator {
    /**
     * v0830 method whitelist (L2 §3.5.1 + §8 S6/S8). GetTask/SubscribeToTask
     * added for S6 (Tier-1) and S8 (Tier-2). CancelTask is NOT whitelisted
     * (S7 not implemented → rejected with VALIDATION_METHOD).
     */
    private static final Set<String> WHITELIST = Set.of(
            "SendMessage", "SendStreamingMessage", "GetTask", "SubscribeToTask");

    /**
     * Inline payload byte limit (whole A2A body, UTF-8). Aligned with
     * {@link com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope#MAX_INLINE_PAYLOAD_BYTES}
     * so G3 rejects oversized inline at the entry (413 {@code PAYLOAD_TOO_LARGE}) before
     * the BUS enqueue layer throws a raw {@code IllegalArgumentException} (500). Large
     * payloads must use {@code payloadRef} (FEAT-012 §5).
     */
    private static final int MAX_INLINE_PAYLOAD_BYTES = 65536;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Validate the raw JSON-RPC body and populate the context (method / agentId /
     * taskId / messageId / contextId).
     *
     * @param rawBody raw JSON-RPC envelope
     * @param ctx     governance context to populate
     * @throws GovernanceException 400 VALIDATION_* on malformed body / bad method / empty agentId
     */
    public void validate(String rawBody, GovernanceContext ctx) {
        checkInlinePayloadSize(rawBody);
        JsonNode root = parseRoot(rawBody);
        validateEnvelope(root);
        String method = text(root, "method").orElse(null);
        if (method == null || method.isBlank()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "Missing method");
        }
        if (!WHITELIST.contains(method)) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_METHOD",
                    "Method not supported: " + method);
        }
        ctx.setMethod(method);

        if ("GetTask".equals(method) || "SubscribeToTask".equals(method)) {
            // S6/S8: validate params.id (NOT params.message.taskId); no message/parts
            String taskId = text(root.path("params"), "id").orElse(null);
            if (taskId == null || taskId.isBlank()) {
                throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_TASK_ID",
                        "params.id is required for " + method);
            }
            ctx.setTaskId(taskId);
            // GetTask optional historyLength (透传到 runtime)
            JsonNode hlNode = root.path("params").path("historyLength");
            if (hlNode.isInt()) {
                ctx.setHistoryLength(hlNode.asInt());
            }
        } else {
            // S2/S3/S4: create or resume via SendMessage/SendStreamingMessage
            JsonNode message = validateMessage(root);
            classifyCreateOrResume(root, message, ctx);
            populateMessageFields(message, ctx);
        }
        ctx.setIdempotencyFingerprint(fingerprintOf(root));
    }

    private JsonNode parseRoot(String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (JsonProcessingException ex) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "Malformed JSON-RPC body");
        }
    }

    private void validateEnvelope(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "JSON-RPC body must be an object");
        }
        if (!"2.0".equals(text(root, "jsonrpc").orElse(null))) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "jsonrpc must be \"2.0\"");
        }
    }

    private static JsonNode validateMessage(JsonNode root) {
        JsonNode message = root.path("params").path("message");
        if (message.isMissingNode() || !message.isObject()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_MESSAGE",
                    "params.message is required and must be an object");
        }
        if (!message.path("parts").isArray()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_MESSAGE",
                    "params.message.parts must be an array");
        }
        return message;
    }

    private static void classifyCreateOrResume(JsonNode root, JsonNode message, GovernanceContext ctx) {
        String taskId = text(message, "taskId").orElse(null);
        if (taskId != null && !taskId.isBlank()) {
            ctx.setTaskId(taskId);
            return;
        }
        // create — parts must not be empty; agentId optional but empty-string is illegal
        if (message.path("parts").isEmpty()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_MESSAGE",
                    "params.message.parts must not be empty for create");
        }
        // check both params.metadata.agentId (spec) and params.agentId (misplaced, ISSUE-99)
        String agentId = text(root.path("params").path("metadata"), "agentId").orElse(null);
        if (agentId == null) {
            agentId = text(root.path("params"), "agentId").orElse(null);
        }
        if (agentId != null && agentId.isBlank()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_AGENT_ID",
                    "agentId must not be empty");
        }
        if (agentId != null) {
            ctx.setAgentId(agentId);
        }
    }

    private static void populateMessageFields(JsonNode message, GovernanceContext ctx) {
        String messageId = text(message, "messageId").orElse(null);
        if (messageId != null && !messageId.isBlank()) {
            ctx.setMessageId(messageId);
        }
        String contextId = text(message, "contextId").orElse(null);
        if (contextId != null && !contextId.isBlank()) {
            ctx.setContextId(contextId);
        }
    }

    /**
     * G3 payload guard: the inline payload (whole A2A body, UTF-8 bytes) must not
     * exceed {@link #MAX_INLINE_PAYLOAD_BYTES}. Aligned with
     * {@link com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope}'s enqueue-layer
     * cap, so G3 rejects oversized inline at the entry with 413
     * {@code PAYLOAD_TOO_LARGE} (stable error body) instead of letting the BUS
     * enqueue layer throw a raw {@code IllegalArgumentException} (500). Large
     * payloads must use {@code payloadRef} (FEAT-012 §5).
     *
     * @param rawBody raw JSON-RPC envelope
     */
    private static void checkInlinePayloadSize(String rawBody) {
        if (rawBody == null) {
            return;
        }
        int size = rawBody.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_INLINE_PAYLOAD_BYTES) {
            throw new GovernanceException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "inline payload exceeds " + MAX_INLINE_PAYLOAD_BYTES + " bytes (UTF-8): " + size
                            + "; use payloadRef for larger payloads");
        }
    }

    /**
     * Compute a stable idempotency fingerprint from the request {@code params}
     * subtree (carries {@code message} + {@code metadata}, i.e. the business body
     * incl. {@code messageId} and {@code agentId}), serialized with sorted keys.
     * Excludes the JSON-RPC envelope fields {@code id} / {@code jsonrpc} /
     * {@code method} so a client retry that regenerates the request {@code id}
     * (JSON-RPC convention) does not break idempotent reuse (FEAT-011 L2 §3.6
     * idempotency key = messageId).
     *
     * @param root parsed JSON-RPC root
     * @return normalized fingerprint string; {@code "{}"} when params absent
     */
    private String fingerprintOf(JsonNode root) {
        JsonNode params = root.path("params");
        if (params.isMissingNode() || params.isNull()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(toSorted(params));
        } catch (JsonProcessingException ex) {
            return params.toString();
        }
    }

    /**
     * Recursively convert a JsonNode to a key-sorted structure (TreeMap for objects,
     * List for arrays) so the serialized fingerprint is stable regardless of the
     * client's JSON field order.
     *
     * @param node a JsonNode (object / array / scalar)
     * @return the sorted Java form (TreeMap / List / scalar)
     */
    private static Object toSorted(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, Object> map = new TreeMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toSorted(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(e -> list.add(toSorted(e)));
            return list;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        // JSON null token — returning the NullNode itself (the node arg when isNull())
        // serializes to 'null' (same as Java null) without a bare 'null' literal in the
        // return statement (G.MET.06). Other non-textual scalars fall back to asText().
        return node.isNull() ? node : node.asText();
    }

    private static Optional<String> text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return (node.isMissingNode() || node.isNull()) ? Optional.empty() : Optional.of(node.asText());
    }
}
