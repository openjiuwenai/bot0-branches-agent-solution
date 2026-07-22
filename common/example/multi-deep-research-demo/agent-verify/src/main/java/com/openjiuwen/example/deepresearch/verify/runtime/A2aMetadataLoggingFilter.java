/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wire-level metadata logger for the verify-agent A2A endpoint — provides
 * FEAT-004 §A-8 (Metadata 转发) evidence. Emits one INFO line per incoming
 * POST /a2a request with the fields most relevant to metadata forwarding:
 *
 * <ul>
 *   <li>{@code method} — SendMessage / SendStreamingMessage / GetTask …</li>
 *   <li>{@code contextId} — session/conversation identity</li>
 *   <li>{@code params.metadata} — request-level metadata (JSON-RPC)</li>
 *   <li>{@code params.message.metadata} — message-level metadata</li>
 * </ul>
 *
 * <p>Reads via {@link ContentCachingRequestWrapper} — parses <em>after</em>
 * downstream has consumed the body so we log what actually reached the
 * A2A controller, not what we speculated it might see.
 *
 * @since 2026-07-21
 */
@Component
public class A2aMetadataLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(A2aMetadataLoggingFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CACHED_BODY_BYTES = 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        boolean isA2aPost = "POST".equalsIgnoreCase(req.getMethod())
                && req.getRequestURI() != null
                && req.getRequestURI().startsWith("/a2a");
        if (!isA2aPost) {
            chain.doFilter(req, resp);
            return;
        }
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req, MAX_CACHED_BODY_BYTES);
        try {
            chain.doFilter(wrapped, resp);
        } finally {
            logMetadata(wrapped);
        }
    }

    private void logMetadata(ContentCachingRequestWrapper wrapped) {
        byte[] body = wrapped.getContentAsByteArray();
        if (body.length == 0) {
            LOG.info("[A2A wire] verify-agent received empty body");
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode params = root.path("params");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("method", text(root.path("method")));
            summary.put("contextId", text(params.path("message").path("contextId")));
            summary.put("params.metadata", nodeToObj(params.path("metadata")));
            summary.put("params.message.metadata", nodeToObj(params.path("message").path("metadata")));
            LOG.info("[A2A wire] verify-agent received: {}", summary);
        } catch (JsonProcessingException e) {
            LOG.warn("[A2A wire] failed to parse body for metadata log: {}", e.toString());
        } catch (IOException e) {
            LOG.warn("[A2A wire] IO error reading body for metadata log: {}", e.toString());
        }
    }

    private static String text(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        return n.asText("");
    }

    private static Object nodeToObj(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        try {
            return MAPPER.treeToValue(n, Object.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return n.toString();
        }
    }
}
