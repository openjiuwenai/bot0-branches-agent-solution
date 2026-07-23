/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import jakarta.servlet.http.HttpServletRequest;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto-configures one host-defined Custom REST entrypoint backed by the A2A request handler.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, RequestHandler.class})
@ConditionalOnProperty(prefix = "openjiuwen.service.custom-rest", name = "query-path")
public class CustomRestAutoConfiguration {
    @Bean
    CustomRestA2ABridge customRestA2ABridge(CustomRestProtocolAdapter adapter, RequestHandler requestHandler,
                                            TaskStore taskStore, ObjectProvider<AgentReadiness> readiness,
                                            @Value("${openjiuwen.service.custom-rest.query-path}") String queryPath) {
        validateQueryPath(queryPath);
        return new CustomRestA2ABridge(adapter, requestHandler, taskStore, readiness.getIfAvailable());
    }

    static void validateQueryPath(String queryPath) {
        if (!StringUtils.hasText(queryPath) || !queryPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "openjiuwen.service.custom-rest.query-path must be a non-blank absolute path pattern");
        }
    }

    @Controller
    static final class CustomRestHandler {
        private static final TypeReference<Map<String, Object>> BODY_TYPE = new TypeReference<>() {
        };

        private final CustomRestA2ABridge bridge;
        private final CustomRestSseTransport sseTransport;
        private final ObjectMapper objectMapper;

        CustomRestHandler(CustomRestA2ABridge bridge, ObjectMapper objectMapper) {
            this.bridge = bridge;
            this.sseTransport = new CustomRestSseTransport(bridge, objectMapper);
            this.objectMapper = objectMapper;
        }

        @PostMapping(path = "${openjiuwen.service.custom-rest.query-path}")
        @ResponseBody
        ResponseEntity<?> handle(HttpServletRequest request,
                                 @PathVariable Map<String, String> pathVariables) throws IOException {
            Map<String, List<String>> headers = headers(request);
            Map<String, List<String>> query = query(request);
            byte[] rawBody = request.getInputStream().readAllBytes();
            CustomRestProtocolAdapter.Context context = new CustomRestProtocolAdapter.Context(
                    headers, pathVariables, query, Map.of());
            try {
                Map<String, Object> body = parseBody(request.getContentType(), rawBody);
                context = new CustomRestProtocolAdapter.Context(headers, pathVariables, query, body);
                boolean acceptsSse = acceptsSse(headers.get(HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT)));
                CustomRestA2ABridge.Prepared prepared = bridge.prepare(context, acceptsSse);
                if (prepared.command().stream()) {
                    SseEmitter emitter = sseTransport.connect(bridge.executeStream(prepared), prepared);
                    return ResponseEntity.ok()
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                            .header(HttpHeaders.CONNECTION, "keep-alive")
                            .header("X-Accel-Buffering", "no")
                            .body(emitter);
                }
                Object response = bridge.executeBlocking(prepared);
                if (!CustomRestA2ABridge.isSerializable(objectMapper, response)) {
                    throw new CustomRestFailure(500, "adapter_execution_failed",
                            "The custom response could not be serialized");
                }
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
            } catch (CustomRestFailure failure) {
                Object errorResponse = bridge.projectError(failure, context);
                if (!CustomRestA2ABridge.isSerializable(objectMapper, errorResponse)) {
                    errorResponse = CustomRestA2ABridge.fallbackError(failure);
                }
                return ResponseEntity.status(failure.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse);
            }
        }

        private Map<String, Object> parseBody(String contentType, byte[] body) {
            if (body.length == 0) {
                return Map.of();
            }
            if (!StringUtils.hasText(contentType) || !isJsonMediaType(contentType)) {
                throw new CustomRestFailure(415, "unsupported_media_type",
                        "A non-empty request body requires a JSON content type");
            }
            try {
                JsonNode root = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
                if (root == null || !root.isObject()) {
                    throw new CustomRestFailure(400, "invalid_json", "The JSON root must be an object");
                }
                return objectMapper.convertValue(root, BODY_TYPE);
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw new CustomRestFailure(400, "invalid_json", "The request body is not valid JSON");
            }
        }

        private static boolean isJsonMediaType(String value) {
            try {
                MediaType mediaType = MediaType.parseMediaType(value);
                return MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                        || mediaType.getSubtype().toLowerCase(Locale.ROOT).endsWith("+json");
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static boolean acceptsSse(List<String> acceptValues) {
            if (acceptValues == null || acceptValues.stream().noneMatch(StringUtils::hasText)) {
                return true;
            }
            try {
                Comparator<MediaType> precedence = Comparator
                        .comparingInt((MediaType mediaType) -> mediaType.isWildcardType() ? 0
                                : mediaType.isWildcardSubtype() ? 1 : 2)
                        .thenComparingDouble(MediaType::getQualityValue);
                return MediaType.parseMediaTypes(acceptValues).stream()
                        .filter(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                        .max(precedence)
                        .map(mediaType -> mediaType.getQualityValue() > 0)
                        .orElse(false);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static Map<String, List<String>> headers(HttpServletRequest request) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            Collections.list(request.getHeaderNames()).forEach(name -> {
                List<String> values = Collections.list(request.getHeaders(name));
                result.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).addAll(values);
            });
            return result;
        }

        private static Map<String, List<String>> query(HttpServletRequest request) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            request.getParameterMap().forEach((key, values) -> result.put(key, List.of(values)));
            return result;
        }
    }
}
