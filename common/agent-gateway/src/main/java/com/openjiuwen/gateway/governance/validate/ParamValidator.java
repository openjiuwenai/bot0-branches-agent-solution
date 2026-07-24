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

import java.util.Set;

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
     * 730 method whitelist (L2 §3.5.1).
     */
    private static final Set<String> WHITELIST = Set.of("SendMessage", "SendStreamingMessage");

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
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (JsonProcessingException ex) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "Malformed JSON-RPC body");
        }
        if (root == null || !root.isObject()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "JSON-RPC body must be an object");
        }
        if (!"2.0".equals(text(root, "jsonrpc"))) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "jsonrpc must be \"2.0\"");
        }
        String method = text(root, "method");
        if (method == null || method.isBlank()) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_JSONRPC",
                    "Missing method");
        }
        if (!WHITELIST.contains(method)) {
            throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_METHOD",
                    "Method not supported: " + method);
        }
        ctx.setMethod(method);

        JsonNode message = root.path("params").path("message");
        String taskId = text(message, "taskId");
        if (taskId != null && !taskId.isBlank()) {
            // resume
            ctx.setTaskId(taskId);
        } else {
            // create — agentId optional but empty-string is illegal
            String agentId = text(root.path("params").path("metadata"), "agentId");
            if (agentId != null && agentId.isBlank()) {
                throw new GovernanceException(HttpStatus.BAD_REQUEST, "VALIDATION_AGENT_ID",
                        "agentId must not be empty");
            }
            if (agentId != null) {
                ctx.setAgentId(agentId);
            }
        }

        String messageId = text(message, "messageId");
        if (messageId != null && !messageId.isBlank()) {
            ctx.setMessageId(messageId);
        }
        String contextId = text(message, "contextId");
        if (contextId != null && !contextId.isBlank()) {
            ctx.setContextId(contextId);
        }
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return (node.isMissingNode() || node.isNull()) ? null : node.asText();
    }
}
