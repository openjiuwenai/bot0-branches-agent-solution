/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * General KB HTTP adapter (Python {@code GeneralKBAdapter}).
 *
 * @since 2026-08-25
 */

public final class GeneralKBAdapter implements KBServiceAdapter {

    /**
     * search.
     *
     * @param query query
     * @param connectionConfig connectionConfig
     * @param knowledgeBases knowledgeBases
     * @param retrievalParams retrievalParams
     * @return result
     * @since 0.1.0
     */

    @Override
    public List<KBSearchResult> search(
            String query,
            Map<String, Object> connectionConfig,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        int topK = KbHttp.intOf(retrievalParams.get("topK"), 10);
        double scoreThreshold = KbHttp.doubleOf(retrievalParams.get("scoreThreshold"), 0.0);
        String endpoint = KbHttp.str(connectionConfig.get("endpoint"));
        Map<String, Object> extra = KbHttp.mapOf(connectionConfig.get("extra_params"));
        String apiKey = KbHttp.str(extra.get("apiKey"));
        if (apiKey.isBlank()) {
            apiKey = KbHttp.str(connectionConfig.get("authorization"));
        }
        if (endpoint.isBlank()) {
            throw new IllegalStateException("General KB endpoint is empty");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("General KB apiKey is empty");
        }
        List<String> datasetIds = externalIds(knowledgeBases);
        if (datasetIds.isEmpty()) {
            throw new IllegalStateException("No valid knowledge_base_ids found in knowledge_bases for General KB");
        }
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json", "Authorization", "Bearer " + apiKey);
        String mode = KbHttp.str(retrievalParams.getOrDefault("searchMode", "doc")).toLowerCase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("knowledge_base_ids", datasetIds);
        body.put("query", query);
        body.put("method", mode.isBlank() ? "doc" : mode);
        body.put("offset", 0);
        body.put("limit", topK);
        body.put("top_k", topK);
        if (scoreThreshold > 0) {
            body.put("search_threshold", scoreThreshold);
        }
        String url = endpoint.replaceAll("/$", "") + "/knowledge-bases/retrieve";
        CustomerHeaderInject.applyToKb(headers);
        List<KBSearchResult> results = parseResponse(KbHttp.postJson(url, headers, body));
        results.sort(Comparator.comparingDouble(KBSearchResult::score).reversed());
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }
        if (scoreThreshold > 0) {
            results.removeIf(r -> r.score() < scoreThreshold);
        }
        return results;
    }

    /**
     * parseResponse.
     *
     * @param respData respData
     * @return hits
     */

    @SuppressWarnings("unchecked")
    public static List<KBSearchResult> parseResponse(Map<String, Object> respData) {
        List<KBSearchResult> results = new ArrayList<>();
        Object list = respData.get("search_result_list");
        if (!(list instanceof List<?> items)) {
            return results;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            m.forEach((k, v) -> doc.put(String.valueOf(k), v));
            String text = KbHttp.str(doc.get("content"));
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>(doc);
            meta.remove("content");
            meta.remove("score");
            results.add(new KBSearchResult()
                    .setText(text)
                    .setScore(KbHttp.doubleOf(doc.get("score"), 0))
                    .setSource(first(doc, "knowledge_base_id", "datasetId"))
                    .setKnowledgeBaseId(first(doc, "knowledge_base_id", "datasetId"))
                    .setFileId(first(doc, "file_id", "fileId", "documentId", "document_id"))
                    .setDocumentName(first(doc, "title", "documentName", "document_name"))
                    .setSubtitle(KbHttp.str(doc.get("subtitle")))
                    .setType(typeOrDefault(doc))
                    .setMetadata(meta));
        }
        return results;
    }

    private static String typeOrDefault(Map<String, Object> doc) {
        String t = first(doc, "type", "doc_type");
        return t.isBlank() ? "doc" : t;
    }

    private static List<String> externalIds(List<Map<String, Object>> knowledgeBases) {
        List<String> ids = new ArrayList<>();
        if (knowledgeBases == null) {
            return ids;
        }
        for (Map<String, Object> kb : knowledgeBases) {
            String ext = KbHttp.str(kb.get("external_id"));
            if (!ext.isBlank()) {
                ids.add(ext);
            }
        }
        return ids;
    }

    private static String first(Map<String, Object> doc, String... keys) {
        for (String k : keys) {
            Object v = doc.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return "";
    }
}
