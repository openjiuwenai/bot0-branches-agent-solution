/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the MCP {@code tools/list} and {@code tools/call} JSON-RPC methods.
 *
 * <p>Two custom tools are exposed:
 * <ul>
 *   <li>{@code search_knowledge_base} — keyword lookup over the fixture index;
 *       returns a list of {@code {uri,title,summary,score}} rows.</li>
 *   <li>{@code get_document_summary} — returns the pre-baked one-paragraph summary
 *       for a given URI (cheap alternative to {@code resources/read} for the LLM).</li>
 * </ul>
 *
 * @since 2026-07-07
 */
public class McpToolHandlers {
    private static final String TOOL_SEARCH = "search_knowledge_base";
    private static final String TOOL_SUMMARY = "get_document_summary";
    private static final int DEFAULT_TOP_K = 5;

    private final DocumentFixtureStore store;

    /**
     * Builds the handler around the fixture store.
     *
     * @param store the fixture store
     */
    public McpToolHandlers(DocumentFixtureStore store) {
        this.store = store;
    }

    /**
     * Serves {@code tools/list}.
     *
     * @return the tools/list result payload
     */
    public Map<String, Object> listTools() {
        String searchDesc = "Keyword search over the bundled research knowledge base. "
                + "Returns matching document URIs with titles and one-line summaries.";
        String summaryDesc = "Fetches the one-paragraph summary for a knowledge-base document. "
                + "Cheaper than reading the full resource body.";
        String uriDesc = "The document URI, as returned by "
                + "search_knowledge_base or resources/list.";
        List<Map<String, Object>> tools = List.of(
                buildToolCard(TOOL_SEARCH,
                        searchDesc,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string",
                                                "description", "Natural-language query, e.g. vendor name + dimension."),
                                        "top_k", Map.of("type", "integer",
                                                "description", "Max results to return (default 5).",
                                                "default", DEFAULT_TOP_K)),
                                "required", List.of("query"))),
                buildToolCard(TOOL_SUMMARY,
                        summaryDesc,
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "uri", Map.of("type", "string",
                                                "description", uriDesc)),
                                "required", List.of("uri"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return result;
    }

    /**
     * Serves {@code tools/call}.
     *
     * @param name tool name
     * @param arguments tool arguments (may be null / empty)
     * @return the tools/call result payload
     * @throws IllegalArgumentException if the tool is unknown or arguments are invalid
     */
    public Map<String, Object> callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        return switch (name) {
            case TOOL_SEARCH -> searchKnowledgeBase(args);
            case TOOL_SUMMARY -> getDocumentSummary(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private Map<String, Object> searchKnowledgeBase(Map<String, Object> args) {
        String query = stringArg(args, "query", "");
        int topK = intArg(args, "top_k", DEFAULT_TOP_K);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DocumentFixtureStore.ScoredMatch match : store.search(query, topK)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uri", match.entry().uri());
            row.put("title", match.entry().title());
            row.put("summary", match.entry().summary());
            row.put("score", match.score());
            rows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("matches", rows);
        return wrapAsToolResult(payload);
    }

    private Map<String, Object> getDocumentSummary(Map<String, Object> args) {
        String uri = stringArg(args, "uri", "");
        DocumentIndexEntry entry = store.findByUri(uri).orElseThrow(
                () -> new IllegalArgumentException("Unknown document uri: " + uri));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uri", entry.uri());
        payload.put("title", entry.title());
        payload.put("summary", entry.summary());
        return wrapAsToolResult(payload);
    }

    private static Map<String, Object> buildToolCard(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", name);
        card.put("description", description);
        card.put("inputSchema", inputSchema);
        return card;
    }

    private static Map<String, Object> wrapAsToolResult(Map<String, Object> payload) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", toJsonSafely(payload));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(block));
        result.put("isError", false);
        return result;
    }

    private static String toJsonSafely(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize tool result", e);
        }
    }

    private static String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object value = args.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
