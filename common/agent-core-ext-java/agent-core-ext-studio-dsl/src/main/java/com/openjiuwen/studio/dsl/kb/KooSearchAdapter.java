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
 * KooSearch HTTP adapter (Python {@code KooSearchAdapter}).
 *
 * @since 2026-08-25
 */
public final class KooSearchAdapter implements KBServiceAdapter {
    @Override
    public List<KBSearchResult> search(
            String query,
            Map<String, Object> connectionConfig,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        int topK = KbHttp.intOf(retrievalParams.get("topK"), 10);
        double scoreThreshold = KbHttp.doubleOf(retrievalParams.get("scoreThreshold"), 0.0);
        String searchMode = KbHttp.str(retrievalParams.getOrDefault("searchMode", "doc"));
        Object tagsObj = retrievalParams.get("tags");
        String endpoint = KbHttp.str(connectionConfig.get("endpoint"));
        Map<String, Object> extra = KbHttp.mapOf(connectionConfig.get("extra_params"));
        String appCode = KbHttp.str(extra.get("AppCode"));
        if (appCode.isBlank()) {
            appCode = KbHttp.str(connectionConfig.get("authorization"));
        }
        if (endpoint.isBlank()) {
            throw new IllegalStateException("KooSearch endpoint is empty");
        }
        if (appCode.isBlank()) {
            throw new IllegalStateException("KooSearch AppCode is empty");
        }
        List<String> datasetIds = externalIds(knowledgeBases);
        if (datasetIds.isEmpty()) {
            throw new IllegalStateException("No valid dataset_ids found in knowledge_bases for KooSearch");
        }
        Map<String, String> headers =
                Map.of("Content-Type", "application/json", "X-Apig-AppCode", appCode);
        String projectId = KbHttp.str(extra.get("project_id"));
        String applicationId = KbHttp.str(extra.get("application_id"));
        String url;
        if (!projectId.isBlank() && !applicationId.isBlank()) {
            url = endpoint.replaceAll("/$", "")
                    + "/"
                    + applicationId
                    + "/v1/"
                    + projectId
                    + "/applications/"
                    + applicationId
                    + "/uni-search/experience/searchtext";
        } else {
            url = endpoint.replaceAll("/$", "") + "/uni-search/experience/searchtext";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo_id", datasetIds.get(0));
        body.put("content", query);
        body.put(
                "extra_repo_ids",
                datasetIds.size() > 1 ? datasetIds.subList(1, datasetIds.size()) : List.of());
        body.put("page_num", 1);
        body.put("page_size", topK);
        body.put("scope", searchMode.isBlank() ? "doc" : searchMode.toLowerCase());
        if (tagsObj instanceof List<?> tags && !tags.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object t : tags) {
                parts.add(String.valueOf(t));
            }
            body.put("filter_string", "tags:(" + String.join(" OR ", parts) + ")");
        }
        // Reuse LakeSearch response shape
        List<KBSearchResult> results =
                LakeSearchAdapter.parseResponse(KbHttp.postJson(url, headers, body), datasetIds.get(0));
        results.sort(Comparator.comparingDouble(KBSearchResult::score).reversed());
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }
        if (scoreThreshold > 0) {
            results.removeIf(r -> r.score() < scoreThreshold);
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
}
