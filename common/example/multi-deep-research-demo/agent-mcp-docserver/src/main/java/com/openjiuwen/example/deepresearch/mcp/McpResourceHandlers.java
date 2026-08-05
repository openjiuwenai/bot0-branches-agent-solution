/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the MCP {@code resources/list} and {@code resources/read} JSON-RPC methods.
 *
 * @since 2026-07-07
 */
public class McpResourceHandlers {
    private final DocumentFixtureStore store;

    /**
     * Builds the handler around the fixture store.
     *
     * @param store the fixture store
     */
    public McpResourceHandlers(DocumentFixtureStore store) {
        this.store = store;
    }

    /**
     * Serves {@code resources/list} — returns a spec-compliant array of resource descriptors.
     *
     * @return the {@code resources/list} result payload
     */
    public Map<String, Object> listResources() {
        List<Map<String, Object>> resources = new ArrayList<>();
        for (DocumentIndexEntry entry : store.list()) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("uri", entry.uri());
            descriptor.put("name", entry.title());
            descriptor.put("description", entry.summary());
            descriptor.put("mimeType", entry.mimeType());
            resources.add(descriptor);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", resources);
        return result;
    }

    /**
     * Serves {@code resources/read} — returns the raw markdown body of the requested URI.
     *
     * @param uri the resource URI
     * @return the {@code resources/read} result payload
     * @throws IOException if the fixture cannot be read
     * @throws IllegalArgumentException if the URI is unknown
     */
    public Map<String, Object> readResource(String uri) throws IOException {
        DocumentIndexEntry entry = store.findByUri(uri).orElseThrow(
                () -> new IllegalArgumentException("Unknown resource uri: " + uri));
        String body = store.read(entry);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", entry.uri());
        content.put("mimeType", entry.mimeType());
        content.put("text", body);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contents", List.of(content));
        return result;
    }
}
