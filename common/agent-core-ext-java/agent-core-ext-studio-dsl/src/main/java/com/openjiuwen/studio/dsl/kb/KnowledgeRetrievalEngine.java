/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * Knowledge retrieval orchestration — strict 1:1 with Python {@code FlowKnowledgeRetrieval}.
 *
 * <p>KB config: inline {@code kbConfig}, or OBS via {@link ObsKnowledgeBaseConfigProvider}.
 * Search: FAQ priority, CLOSE filter, CUSTOM path, KooSearchInside multi-KB, recallThreshold/topK.
 * Redis image/file cache via {@link KnowledgeRetrievalCacheStore}.
 * Tests: {@code mockDocuments} stub, or {@link KBAdapterFactory#register}.
 *
 * @since 2026-08-25
 */

public final class KnowledgeRetrievalEngine {
    private static final Pattern IMAGE_ID_PATTERN = Pattern.compile("\\{(img-[a-z0-9-]+)}", Pattern.CASE_INSENSITIVE);
    private static final String RETRIEVAL_IMAGE_FORMAT = "![img](https://agent_arts_knowledge_img_url/%s)";
    private static final String FILE_TYPE_DOC = "doc";
    private static final String FILE_TYPE_FAQ = "faq";

    private final String nodeId;
    private final Map<String, Object> configs;

    /**
     * KnowledgeRetrievalEngine.
     *
     * @param nodeId nodeId
     * @param configs configs
     */

    public KnowledgeRetrievalEngine(String nodeId, Map<String, Object> configs) {
        this.nodeId = nodeId;
        this.configs = configs == null ? Map.of() : configs;
    }

    /**
     * invoke.
     *
     * @param inputs inputs
     * @param session session
     * @return userFields
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        // Explicit mockDocuments (test / host stub — not in Python, keeps FEAT smoke)
        Object docs = configs.get("mockDocuments");
        if (docs instanceof List<?> list) {
            Map<String, Object> uf = new LinkedHashMap<>();
            uf.put("output_list", list);
            uf.put("documents", list);
            uf.put("knowledgeResults", list);
            return uf;
        }

        String query = resolveQuery(inputs, session);
        if (query == null || query.isBlank()) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.knowledgeRetrieval",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Query must be a non-empty string");
        }

        Map<String, Object> kbConfig = resolveKbConfig();
        Map<String, Object> connection = KbHttp.mapOf(kbConfig.get("connection"));
        List<Map<String, Object>> knowledgeBases = listMaps(kbConfig.get("knowledge_bases"));
        Map<String, Object> retrievalParams = new LinkedHashMap<>(KbHttp.mapOf(kbConfig.get("retrieval_params")));
        // merge IR retrievalConfig
        Object rc = configs.get("retrievalConfig");
        if (rc instanceof Map<?, ?> m) {
            m.forEach((k, v) -> retrievalParams.putIfAbsent(String.valueOf(k), v));
        }

        String connectorType = KbHttp.str(connection.getOrDefault("connector_type", "LakeSearch"));
        KBServiceAdapter adapter;
        try {
            adapter = KBAdapterFactory.create(connectorType);
        } catch (IllegalArgumentException e) {
            throw new NodeExecutionException(
                    nodeId, "jiuwen.knowledgeRetrieval", NodeCauseCode.NODE_CONFIG_INVALID, e.getMessage(), e);
        }

        List<KBSearchResult> results;
        try {
            results = searchKnowledgeRepo(adapter, query, connection, knowledgeBases, retrievalParams);
            if (!isCustomSource(connection)) {
                results = normalizeResults(results, knowledgeBases, retrievalParams);
            }
        } catch (RuntimeException e) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.knowledgeRetrieval",
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    e.getMessage(),
                    e);
        }

        String retrievalId = UUID.randomUUID().toString().replace("-", "");
        int i = 1;
        for (KBSearchResult r : results) {
            r.setRetrievalId(retrievalId);
            if (r.serialNumber() == 0) {
                r.setSerialNumber(i++);
            }
        }

        List<Map<String, Object>> outputList = new ArrayList<>();
        for (KBSearchResult r : results) {
            outputList.add(r.toOutputMap());
        }
        // Python invoke: userFields = { output_list }; keep documents/knowledgeResults aliases for FEAT hosts
        Map<String, Object> uf = new LinkedHashMap<>();
        uf.put("output_list", outputList);
        uf.put("documents", outputList);
        uf.put("knowledgeResults", outputList);
        uf.put("query", query);
        return uf;
    }

    List<KBSearchResult> searchKnowledgeRepo(
            KBServiceAdapter adapter,
            String query,
            Map<String, Object> connection,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        if (isCustomSource(connection)) {
        return retrieveCustom(adapter, query, connection, knowledgeBases, retrievalParams);
    }
        List<Map<String, Object>> active = new ArrayList<>();
        for (Map<String, Object> kb : knowledgeBases) {
            if (!"CLOSE".equalsIgnoreCase(KbHttp.str(kb.get("status")))) {
                active.add(kb);
            }
        }
        if (active.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = new LinkedHashMap<>(retrievalParams);
        List<Object> tags = collectTags(active);
        if (!tags.isEmpty()) {
            params.put("tags", tags);
        }
        int topK = KbHttp.intOf(params.get("topK"), 10);
        String connectorType = KbHttp.str(connection.get("connector_type"));
        List<KBSearchResult> results;
        if ("koosearchinside".equalsIgnoreCase(connectorType) && active.size() > 1) {
            results = multiRetrieve(adapter, query, connection, active, params);
        } else {
            results = searchWithFaqFallback(adapter, query, connection, active, params);
        }
        results = new ArrayList<>(results);
        double recall = KbHttp.doubleOf(
                params.containsKey("recallThreshold") ? params.get("recallThreshold") : params.get("scoreThreshold"),
                0);
        if (recall > 0) {
            results.removeIf(r -> r.score() < recall);
        }
        results.sort(Comparator.comparingDouble(KBSearchResult::score).reversed());
        if (results.size() > topK) {
            results = new ArrayList<>(results.subList(0, topK));
        }
        return results;
    }

    private List<KBSearchResult> searchWithFaqFallback(
            KBServiceAdapter adapter,
            String query,
            Map<String, Object> connection,
            List<Map<String, Object>> kbs,
            Map<String, Object> retrievalParams) {
        boolean needFaq = Boolean.TRUE.equals(retrievalParams.get("needExtrasFaqSearch"))
                || "true".equalsIgnoreCase(String.valueOf(retrievalParams.get("needExtrasFaqSearch")));
        if (needFaq) {
            Map<String, Object> faqParams = new LinkedHashMap<>(retrievalParams);
            faqParams.put("searchMode", "faq");
            List<KBSearchResult> faq = adapter.search(query, connection, kbs, faqParams);
            if (!faq.isEmpty()) {
                for (KBSearchResult r : faq) {
                    r.setType("faq");
                }
                return faq;
            }
        }
        List<KBSearchResult> results = adapter.search(query, connection, kbs, retrievalParams);
        String type = "faq".equalsIgnoreCase(KbHttp.str(retrievalParams.get("searchMode"))) ? "faq" : "doc";
        for (KBSearchResult r : results) {
            r.setType(type);
        }
        return results;
    }

    private List<KBSearchResult> multiRetrieve(
            KBServiceAdapter adapter,
            String query,
            Map<String, Object> connection,
            List<Map<String, Object>> kbs,
            Map<String, Object> retrievalParams) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> kb : kbs) {
            String conn = KbHttp.str(kb.getOrDefault(
                    "connection_id", kb.getOrDefault("knowledge_base_connection_id", "default")));
            groups.computeIfAbsent(conn, k -> new ArrayList<>()).add(kb);
        }
        List<KBSearchResult> all = new ArrayList<>();
        for (List<Map<String, Object>> group : groups.values()) {
            try {
                all.addAll(searchWithFaqFallback(adapter, query, connection, group, retrievalParams));
            } catch (RuntimeException ignored) {
                // Python logs and continues
            }
        }
        int topK = KbHttp.intOf(retrievalParams.get("topK"), 10);
        all.sort(Comparator.comparingDouble(KBSearchResult::score).reversed());
        return all.size() > topK ? new ArrayList<>(all.subList(0, topK)) : all;
    }

    private List<KBSearchResult> retrieveCustom(
            KBServiceAdapter adapter,
            String query,
            Map<String, Object> connection,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        Map<String, Object> customParams = new LinkedHashMap<>(retrievalParams);
        List<Object> tags = collectTags(knowledgeBases);
        if (!tags.isEmpty()) {
            customParams.put("tags", tags);
        }
        customParams.remove("scoreThreshold");
        customParams.remove("recallThreshold");
        List<Map<String, Object>> customKbs = new ArrayList<>();
        for (Map<String, Object> kb : knowledgeBases) {
            Map<String, Object> item = new LinkedHashMap<>(kb);
            String id = KbHttp.str(kb.get("knowledge_base_id"));
            if (!id.isBlank()) {
                item.put("external_id", id);
            }
            customKbs.add(item);
        }
        return adapter.search(query, connection, customKbs, customParams);
    }

    List<KBSearchResult> normalizeResults(
            List<KBSearchResult> results,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams) {
        Map<String, String> externalToInternal = new LinkedHashMap<>();
        Map<String, String> internalToType = new LinkedHashMap<>();
        for (Map<String, Object> kb : knowledgeBases) {
            String internalId = KbHttp.str(kb.get("knowledge_base_id"));
            String externalId = KbHttp.str(kb.get("external_id"));
            String kbType = KbHttp.str(kb.get("type"));
            if (!internalId.isBlank()) {
                externalToInternal.put(internalId, internalId);
                internalToType.put(internalId, kbType);
            }
            if (!externalId.isBlank() && !internalId.isBlank()) {
                externalToInternal.put(externalId, internalId);
            }
        }
        boolean retrieveImage = Boolean.TRUE.equals(retrievalParams.get("retrieveImage"))
                || "true".equalsIgnoreCase(String.valueOf(retrievalParams.get("retrieveImage")));
        int ttlSeconds = KnowledgeRetrievalCacheStore.defaultTtlSeconds();
        Map<String, String> fileCache = new LinkedHashMap<>();
        for (KBSearchResult item : results) {
            String sourceId = item.source().isBlank() ? item.knowledgeBaseId() : item.source();
            String internalId = externalToInternal.getOrDefault(sourceId, externalToInternal.get(item.knowledgeBaseId()));
            if (internalId != null) {
                item.setKnowledgeBaseId(internalId);
                item.setKnowledgeBaseType(internalToType.getOrDefault(internalId, item.knowledgeBaseType()));
            }
            String realFileId = item.fileId();
            if (!realFileId.isBlank() && !item.knowledgeBaseId().isBlank()) {
                String fileType = FILE_TYPE_FAQ.equals(item.type()) ? FILE_TYPE_FAQ : FILE_TYPE_DOC;
                String cacheKey = item.knowledgeBaseId() + ":" + realFileId;
                String virtual = fileCache.computeIfAbsent(cacheKey, k -> UUID.randomUUID().toString());
                item.setFileId(virtual);
                KnowledgeRetrievalCacheStore.set(
                        KnowledgeRetrievalCacheStore.FILE_PREFIX + virtual,
                        item.knowledgeBaseId() + "," + fileType + "," + realFileId,
                        ttlSeconds);
            }
            ImageReplaceResult processed = replaceImageIds(item.text(), retrieveImage);
            item.setText(processed.content());
            if (!processed.imageAccessMap().isEmpty() && !item.knowledgeBaseId().isBlank()) {
                processed.imageAccessMap().forEach((imageId, accessKey) ->
                        KnowledgeRetrievalCacheStore.set(
                                KnowledgeRetrievalCacheStore.IMAGE_PREFIX + accessKey,
                                item.knowledgeBaseId() + "," + imageId,
                                ttlSeconds));
            }
        }
        return results;
    }

    private record ImageReplaceResult(String content, Map<String, String> imageAccessMap) {}

    private static ImageReplaceResult replaceImageIds(String content, boolean retrieveImage) {
        if (content == null || content.isEmpty()) {
        return new ImageReplaceResult("", Map.of());
    }
        if (!retrieveImage) {
            return new ImageReplaceResult(IMAGE_ID_PATTERN.matcher(content).replaceAll(""), Map.of());
        }
        Map<String, String> imageAccessMap = new LinkedHashMap<>();
        Matcher m = IMAGE_ID_PATTERN.matcher(content);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String imageId = m.group(1);
            String accessKey = generateImageAccessKey();
            imageAccessMap.put(imageId, accessKey);
            m.appendReplacement(sb, Matcher.quoteReplacement(String.format(RETRIEVAL_IMAGE_FORMAT, accessKey)));
        }
        m.appendTail(sb);
        return new ImageReplaceResult(sb.toString(), imageAccessMap);
    }

    private static String generateImageAccessKey() {
        String uuidText = UUID.randomUUID().toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(uuidText.getBytes());
    }

    static boolean isCustomSource(Map<String, Object> connection) {
        return "CUSTOM".equalsIgnoreCase(KbHttp.str(connection.get("knowledge_source")));
    }
    private Map<String, Object> resolveKbConfig() {
        Object inline = configs.get("kbConfig");
        if (inline instanceof Map<?, ?>) {
            return KbHttp.mapOf(inline);
        }
        String connectionId = KbHttp.str(configs.get("connectionId"));
        if (!connectionId.isBlank()) {
            return KnowledgeBaseConfigProviders.get().getKbConfig(configs);
        }
        // Build minimal config from IR fields when host embeds connection under configs
        Object connection = configs.get("connection");
        Object kbs = configs.get("knowledge_bases");
        if (connection instanceof Map<?, ?> || kbs instanceof List<?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("connection", connection instanceof Map<?, ?> ? KbHttp.mapOf(connection) : Map.of());
            out.put("knowledge_bases", listMaps(kbs));
            out.put("retrieval_params", KbHttp.mapOf(configs.get("retrievalConfig")));
            return out;
        }
        throw new NodeExecutionException(
                nodeId,
                "jiuwen.knowledgeRetrieval",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "kbConfig missing; provide inline kbConfig, connectionId (OBS),"
                        + " or mockDocuments / core KnowledgeRetrievalExecutable");
    }

    private String resolveQuery(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> uf = userFieldsOf(inputs);
        Object q = uf.get("query");
        if (q instanceof String s && !s.isBlank()) {
            return s;
        }
        if (inputs != null && inputs.get("query") instanceof String s2 && !s2.isBlank()) {
            return s2;
        }
        if (session != null) {
            try {
                Object gq = session.getGlobalState("query");
                if (gq instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object startUf = session.getState("node_start.userFields.query");
                if (startUf instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object startSf = session.getState("node_start.systemFields.query");
                if (startSf instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object tq = session.getState("query");
                if (tq instanceof String s && !s.isBlank()) {
                    return s;
                }
                Object startUserFields = session.getState("node_start.userFields");
                if (startUserFields instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getValue() instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        return "";
    }

    private static List<Object> collectTags(List<Map<String, Object>> kbs) {
        List<Object> tags = new ArrayList<>();
        for (Map<String, Object> kb : kbs) {
            Object t = kb.get("tags");
            if (t instanceof List<?> list) {
                tags.addAll(list);
            } else if (t != null) {
                tags.add(t);
            }
        }
        return tags;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listMaps(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                out.add(KbHttp.mapOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }
}
