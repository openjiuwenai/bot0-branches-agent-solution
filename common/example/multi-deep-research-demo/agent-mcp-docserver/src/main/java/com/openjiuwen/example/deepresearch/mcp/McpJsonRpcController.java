/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single JSON-RPC endpoint dispatching the five MCP methods used by
 * {@code agent-core-java}'s {@code AbstractHttpMcpClient}:
 * {@code initialize / tools/list / tools/call / resources/list / resources/read}.
 *
 * <p>Errors are always returned as a JSON-RPC error envelope with HTTP 200 so the client
 * can surface a structured message instead of a servlet-level 500.
 *
 * @since 2026-07-07
 */
@RestController
public class McpJsonRpcController {
    private static final Logger LOG = LoggerFactory.getLogger(McpJsonRpcController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERIALIZATION_FALLBACK =
            "{\"jsonrpc\":\"2.0\",\"id\":null,"
                    + "\"error\":{\"code\":-32603,\"message\":\"Serialization failure\"}}";

    private final McpDocServerProperties props;
    private final McpResourceHandlers resourceHandlers;
    private final McpToolHandlers toolHandlers;

    /**
     * Builds the controller.
     *
     * @param props external configuration
     * @param resourceHandlers resource JSON-RPC handlers
     * @param toolHandlers tool JSON-RPC handlers
     */
    @Autowired
    public McpJsonRpcController(McpDocServerProperties props,
                                McpResourceHandlers resourceHandlers,
                                McpToolHandlers toolHandlers) {
        this.props = props;
        this.resourceHandlers = resourceHandlers;
        this.toolHandlers = toolHandlers;
    }

    /**
     * Serves JSON-RPC POSTs at the configured endpoint. Path defaults to {@code /mcp}
     * but the client only needs to know whatever {@code server_path} was configured on it.
     *
     * @param rawBody the raw JSON-RPC request body
     * @return the JSON-RPC response with content-type {@code application/json}
     */
    @PostMapping(value = {"/mcp", "/mcp/"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String rawBody) {
        Object id = null;
        try {
            JsonNode root = MAPPER.readTree(rawBody);
            id = extractId(root);
            String method = root.path("method").asText("");
            Map<String, Object> params = extractParams(root);
            Map<String, Object> result = dispatch(method, params);
            return ResponseEntity.ok(serializeSuccess(id, result));
        } catch (IllegalArgumentException e) {
            LOG.info("MCP method error: {}", e.getMessage());
            return ResponseEntity.ok(serializeError(id, -32602, e.getMessage()));
        } catch (java.io.IOException e) {
            LOG.warn("MCP parse error", e);
            return ResponseEntity.ok(serializeError(id, -32700, "Parse error: " + e.getMessage()));
        } catch (Exception e) {
            LOG.error("MCP internal error", e);
            return ResponseEntity.ok(serializeError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private Map<String, Object> dispatch(String method, Map<String, Object> params) throws Exception {
        return switch (method) {
            case "initialize" -> handleInitialize();
            case "tools/list" -> toolHandlers.listTools();
            case "tools/call" -> toolHandlers.callTool(
                    stringParam(params, "name"),
                    mapParam(params.get("arguments")));
            case "resources/list" -> resourceHandlers.listResources();
            case "resources/read" -> resourceHandlers.readResource(stringParam(params, "uri"));
            case "ping" -> Map.of();
            default -> throw new IllegalArgumentException("Method not found: " + method);
        };
    }

    private Map<String, Object> handleInitialize() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("resources", Map.of("listChanged", false));
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", props.getServerName());
        serverInfo.put("version", props.getServerVersion());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private static Object extractId(JsonNode root) {
        JsonNode idNode = root.path("id");
        if (idNode.isMissingNode() || idNode.isNull()) {
            return NullNode.instance;
        }
        if (idNode.isNumber()) {
            return idNode.numberValue();
        }
        return idNode.asText();
    }

    private static Map<String, Object> extractParams(JsonNode root) {
        JsonNode paramsNode = root.path("params");
        if (paramsNode.isMissingNode() || paramsNode.isNull() || !paramsNode.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapParam(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required param: " + key);
        }
        return String.valueOf(value);
    }

    private String serializeSuccess(Object id, Map<String, Object> result) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("result", result);
            return MAPPER.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("Failed to serialize MCP response", e);
            return SERIALIZATION_FALLBACK;
        }
    }

    private String serializeError(Object id, int code, String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("message", message);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", id);
            envelope.put("error", error);
            return MAPPER.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.error("Failed to serialize MCP error response", e);
            return SERIALIZATION_FALLBACK;
        }
    }
}
