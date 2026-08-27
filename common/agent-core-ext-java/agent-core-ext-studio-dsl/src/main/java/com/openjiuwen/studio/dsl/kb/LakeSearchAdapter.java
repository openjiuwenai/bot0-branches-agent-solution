/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LakeSearch HTTP adapter (Python {@code LakeSearchAdapter}; BASIC / TOKEN / KERBEROS).
 *
 * @since 2026-08-25
 */
public final class LakeSearchAdapter implements KBServiceAdapter {
    private static final Set<String> EXCLUDED_META = Set.of(
            "content", "text", "score", "file_id", "fileId", "chunk_id", "chunkId",
            "title", "subtitle", "doc_type", "docType", "repo_id", "repoId");

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
        String authMode = KbHttp.str(connectionConfig.getOrDefault("auth_mode", "BASIC"));
        String authorization = KbHttp.str(connectionConfig.get("authorization"));
        Map<String, Object> extra = KbHttp.mapOf(connectionConfig.get("extra_params"));
        if (endpoint.isBlank()) {
            throw new IllegalStateException("LakeSearch endpoint is empty");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if ("KERBEROS".equalsIgnoreCase(authMode)) {
            Map<String, Object> kerberosConfig = KerberosAuth.extractKerberosConfig(extra);
            if (kerberosConfig == null) {
                throw new IllegalArgumentException(
                        "KERBEROS auth mode requires: host_names, cluster_ips, user_keytab_file, krb5_file"
                                + " in connection params");
            }
            @SuppressWarnings("unchecked")
            java.util.List<String> hostNames = (java.util.List<String>) kerberosConfig.get("host_names");
            String hostname = hostNames != null && !hostNames.isEmpty() ? hostNames.get(0) : "";
            if (hostname.isBlank()) {
                try {
                    java.net.URI uri = java.net.URI.create(endpoint);
                    hostname = uri.getHost();
                } catch (Exception ignored) {
                    hostname = "";
                }
            }
            headers.put("Authorization", KerberosAuth.buildNegotiateAuthorization(hostname, kerberosConfig));
        } else if (!authorization.isBlank()) {
            if ("BASIC".equalsIgnoreCase(authMode)) {
                headers.put("Authorization", "Basic " + authorization);
            } else if ("TOKEN".equalsIgnoreCase(authMode)) {
                headers.put("Authorization", "Bearer " + authorization);
            } else {
                headers.put("Authorization", authorization);
            }
        }
        String projectId = KbHttp.str(extra.get("project_id"));
        String appId = KbHttp.str(extra.get("app_id"));
        if (projectId.isBlank() || appId.isBlank()) {
            throw new IllegalArgumentException(
                    "LakeSearch connection requires project_id and app_id in connection params");
        }
        List<String> repoIds = externalIds(knowledgeBases);
        if (repoIds.isEmpty()) {
            return List.of();
        }
        CustomerHeaderInject.applyToKb(headers);
        String url = endpoint.replaceAll("/$", "")
                + "/v1/"
                + projectId
                + "/applications/"
                + appId
                + "/uni-search/experience/searchtext";
        String scope = switch (searchMode.toLowerCase()) {
            case "faq" -> "faq";
            case "keyword" -> "keyword";
            case "mix" -> "mix";
            default -> "doc";
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo_id", repoIds.get(0));
        body.put("content", query);
        body.put("page_num", 1);
        body.put("page_size", Math.min(topK, 50));
        body.put("scope", scope);
        if (repoIds.size() > 1) {
            body.put("extra_repo_ids", repoIds.subList(1, repoIds.size()));
        }
        if (tagsObj instanceof List<?> tags && !tags.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Object t : tags) {
                parts.add(String.valueOf(t));
            }
            body.put("filter_string", "tags:(" + String.join(" OR ", parts) + ")");
        }
        List<KBSearchResult> results = parseResponse(KbHttp.postJson(url, headers, body), repoIds.get(0));
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
     * @param source default source
     * @return hits
     */
    @SuppressWarnings("unchecked")
    public static List<KBSearchResult> parseResponse(Map<String, Object> respData, String source) {
        List<KBSearchResult> results = new ArrayList<>();
        Object list = respData.get("doc_list");
        if (!(list instanceof List<?>)) {
            list = respData.get("docList");
        }
        if (!(list instanceof List<?>)) {
            Object data = respData.get("data");
            if (data instanceof Map<?, ?> dm) {
                list = dm.get("doc_list");
            }
        }
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
                text = KbHttp.str(doc.get("text"));
            }
            if (text.isBlank()) {
                continue;
            }
            String repo = KbHttp.str(doc.get("repo_id"));
            if (repo.isBlank()) {
                repo = KbHttp.str(doc.get("repoId"));
            }
            if (repo.isBlank()) {
                repo = source;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            doc.forEach((k, v) -> {
                if (!EXCLUDED_META.contains(k)) {
                    meta.put(k, v);
                }
            });
            String docName = KbHttp.str(doc.get("title"));
            String subtitle = KbHttp.str(doc.get("subtitle"));
            if (subtitle.isBlank()) {
                subtitle = docName;
            }
            String type = KbHttp.str(doc.get("doc_type"));
            if (type.isBlank()) {
                type = KbHttp.str(doc.get("docType"));
            }
            if (type.isBlank()) {
                type = KbHttp.str(doc.getOrDefault("type", "doc"));
            }
            results.add(new KBSearchResult()
                    .setText(text)
                    .setScore(KbHttp.doubleOf(doc.get("score"), 0))
                    .setSource(repo)
                    .setKnowledgeBaseId(repo)
                    .setFileId(first(doc, "file_id", "fileId", "chunk_id", "chunkId"))
                    .setDocumentName(docName)
                    .setSubtitle(subtitle)
                    .setKnowledgeBaseType(first(doc, "knowledge_base_type", "knowledgeBaseType"))
                    .setType(type)
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
