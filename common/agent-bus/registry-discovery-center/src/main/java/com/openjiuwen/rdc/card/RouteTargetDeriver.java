/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Optional;

/**
 * Derives internal route target JSON and contract version from provider base URL + Card.
 *
 * @since 0.1.0 (2026)
 */
public final class RouteTargetDeriver {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RouteTargetDeriver() {

    }

    /**
     * derive.
     *
     * @param internalBaseUrl internalBaseUrl
     * @param cardJson cardJson
     * @param routeKeyFallback routeKeyFallback
     * @return result
     * @since 0.1.0
     */
    public static DerivedRoute derive(String internalBaseUrl, String cardJson, String routeKeyFallback) {
        try {
            JsonNode root = MAPPER.readTree(cardJson);
            JsonNode selected = firstCompatibleJsonRpcInterface(root)
                    .orElseThrow(() -> new IllegalArgumentException("no JSON-RPC interface in card"));
            String interfacePath = textOrNull(selected, "url").orElse(null);
            if (interfacePath == null || interfacePath.isBlank()) {
                interfacePath = routeKeyFallback;
            }
            validateInterfaceUrl(internalBaseUrl, interfacePath);
            String base = internalBaseUrl.replaceAll("/$", "");
            String resolvedPath = interfacePath.startsWith("/") ? interfacePath : "/" + interfacePath;
            String contractVersion = textOrNull(selected, "protocolVersion").orElse(null);
            if (contractVersion == null || contractVersion.isBlank()) {
                contractVersion = textOrNull(selected, "version").orElse(null);
            }
            ObjectNode target = MAPPER.createObjectNode();
            target.put("endpointUrl", base);
            target.put("routeKey", resolvedPath);
            target.put("binding", "JSONRPC");
            if (contractVersion != null && !contractVersion.isBlank()) {
                target.put("protocolVersion", contractVersion);
            }
            return new DerivedRoute(MAPPER.writeValueAsString(target), contractVersion);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
                throw new IllegalArgumentException("failed to derive route target", ex);
            }
    }

    /**
     * deriveJson.
     *
     * @param internalBaseUrl internalBaseUrl
     * @param cardJson cardJson
     * @param routeKeyFallback routeKeyFallback
     * @return result
     * @since 0.1.0
     */
    public static String deriveJson(String internalBaseUrl, String cardJson, String routeKeyFallback) {
        return derive(internalBaseUrl, cardJson, routeKeyFallback).routeTargetJson();
    }

    /**
     * agentNameFromCard.
     *
     * @param cardJson cardJson
     * @return result
     * @since 0.1.0
     */
    public static String agentNameFromCard(String cardJson) {
        try {
            JsonNode root = MAPPER.readTree(cardJson);
            JsonNode name = root.get("name");
            return name != null && name.isTextual() ? name.asText() : "unknown-agent";
        } catch (JsonProcessingException ex) {
            return "unknown-agent";
        }
    }

    static void validateInterfaceUrl(String internalBaseUrl, String interfaceUrl) {
        if (interfaceUrl == null || interfaceUrl.isBlank() || interfaceUrl.startsWith("/")) {
            return;
        }
        try {
            URI base = URI.create(internalBaseUrl.replaceAll("/$", ""));
            URI iface = URI.create(interfaceUrl);
            if (iface.getHost() != null && base.getHost() != null
                    && !iface.getHost().equalsIgnoreCase(base.getHost())) {
                throw new IllegalArgumentException(
                        "interface URL host must match provider baseUrl or be relative");
            }
        } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid interface URL: " + interfaceUrl, ex);
            }
    }

    private static Optional<JsonNode> firstCompatibleJsonRpcInterface(JsonNode root) {
        JsonNode interfaces = root.get("supportedInterfaces");
        if (interfaces == null || !interfaces.isArray()) {
            return Optional.empty();
        }
        for (JsonNode iface : interfaces) {
            if (AgentCardValidator.isJsonRpcNode(iface)) {
                return Optional.of(iface);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && child.isTextual() ? Optional.of(child.asText()) : Optional.empty();
    }

    /**
     * DerivedRoute.
     *
     * @param routeTargetJson routeTargetJson
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
    public record DerivedRoute(String routeTargetJson, String contractVersion) {
        }
    }