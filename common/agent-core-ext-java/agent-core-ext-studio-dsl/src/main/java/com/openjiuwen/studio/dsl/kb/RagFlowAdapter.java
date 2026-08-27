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
 * RagFlow HTTP adapter (Python {@code RagFlowAdapter}).
 *
 * @since 2026-08-25
 */
public final class RagFlowAdapter implements KBServiceAdapter {
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
        String authorization = KbHttp.str(extra.get("APIKey"));
        if (authorization.isBlank()) {
            authorization = KbHttp.str(connectionConfig.get("authorization"));
        }
        if (endpoint.isBlank()) {
            throw new IllegalStateException("RAGFlow endpoint is empty");
        }
        if (authorization.isBlank()) {
            throw new IllegalStateException("RAGFlow authorization is empty");
        }
        List<String> datasetIds = externalIds(knowledgeBases);
        if (datasetIds.isEmpty()) {
            throw new IllegalStateException("No valid dataset_ids found in knowledge_bases for RAGFlow");
        }
        Map<String, String> headers =
                Map.of("Content-Type", "application/json", "Authorization", "Bearer " + authorization);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", query);
        body.put("dataset_ids", datasetIds);
        body.put("page", 1);
        body.put("page_size", topK);
        if (scoreThreshold > 0) {
            body.put("similarity_threshold", scoreThreshold);
        }
        if (retrievalParams.containsKey("vectorSimilarityWeight")) {
            body.put("vector_similarity_weight", retrievalParams.get("vectorSimilarityWeight"));
        }
        if (retrievalParams.containsKey("keyword")) {
            body.put("keyword", retrievalParams.get("keyword"));
        }
        if (retrievalParams.containsKey("rerankId")) {
            body.put("rerank_id", retrievalParams.get("rerankId"));
        }
        if (retrievalParams.containsKey("highlight")) {
            body.put("highlight", retrievalParams.get("highlight"));
        }
        if (retrievalParams.containsKey("crossLanguages")) {
            body.put("cross_languages", retrievalParams.get("crossLanguages"));
        }
        String url = endpoint.replaceAll("/$", "") + "/api/v1/retrieval";
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
        Object code = respData.get("code");
        if (code instanceof Number n && n.intValue() != 0) {
            throw new IllegalStateException(
                    "RAGFlow API returned non-zero code: " + code + ", message: " + respData.get("message"));
        }
        List<KBSearchResult> results = new ArrayList<>();
        Object data = respData.get("data");
        if (!(data instanceof Map<?, ?> dm)) {
            return results;
        }
        Object chunks = dm.get("chunks");
        if (!(chunks instanceof List<?> items)) {
            return results;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> chunk = new LinkedHashMap<>();
            m.forEach((k, v) -> chunk.put(String.valueOf(k), v));
            String text = KbHttp.str(chunk.get("content"));
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> meta = new LinkedHashMap<>(chunk);
            meta.remove("content");
            meta.remove("similarity");
            String docName = first(chunk, "document_keyword", "docnm_kwd", "title", "document_name", "documentName");
            double score = KbHttp.doubleOf(chunk.get("similarity"), 0);
            String kbId = first(chunk, "dataset_id", "datasetId", "kb_id");
            results.add(new KBSearchResult()
                    .setText(text)
                    .setScore(score)
                    .setSource(kbId)
                    .setKnowledgeBaseId(kbId)
                    .setFileId(first(chunk, "document_id", "documentId", "doc_id", "chunk_id"))
                    .setDocumentName(docName)
                    .setSubtitle(docName)
                    .setKnowledgeBaseType(first(chunk, "knowledge_base_type", "knowledgeBaseType"))
                    .setType(score > 0.9 ? "faq" : "doc")
                    .setMetadata(meta));
        }
        return results;
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
