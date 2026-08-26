/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.studio.dsl.contract.KnowledgeBaseConfigProvider;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OBS-backed KB config loader (Python {@code OBSKnowledgeBaseConfigProvider}).
 *
 * @since 2026-08-26
 */
public final class ObsKnowledgeBaseConfigProvider implements KnowledgeBaseConfigProvider {
    static final Set<String> SECRET_PARAM_CODES =
            Set.of("apiKey", "APIKey", "AppCode", "password", "authorization");

    private static final Logger LOG = Logger.getLogger(ObsKnowledgeBaseConfigProvider.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONNECTION_PATH = "kb-connection/ir/connection/%s.json";
    private static final String KB_PATH = "kb-connection/ir/knowledge-base/%s.json";
    private static final long CACHE_TTL_MS = 300_000L;

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getKbConfig(Map<String, Object> nodeConfigs) {
        Map<String, Object> configs = nodeConfigs == null ? Map.of() : new LinkedHashMap<>(nodeConfigs);
        String connectionId = KbHttp.str(configs.get("connectionId"));
        if (connectionId.isBlank()) {
            LOG.warning("Knowledge retrieval node missing connectionId, falling back to environment variables");
            return new EnvVarKnowledgeBaseConfigProvider().getKbConfig(configs);
        }

        Map<String, Object> connection = loadConnection(connectionId);
        if (connection == null) {
            connection = new LinkedHashMap<>();
            connection.put("connection_id", connectionId);
        }
        mergeAuthHeaders(connection);

        List<Map<String, Object>> knowledgeBases = new ArrayList<>();
        boolean isCustom = "CUSTOM".equalsIgnoreCase(KbHttp.str(connection.get("knowledge_source")));
        Object kbIds = configs.get("knowledgeBaseIds");
        if (kbIds instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String kbId = String.valueOf(item);
                Map<String, Object> kbRef = loadKbReference(kbId);
                if (kbRef != null) {
                    knowledgeBases.add(kbRef);
                } else if (isCustom) {
                    Map<String, Object> synthetic = new LinkedHashMap<>();
                    synthetic.put("knowledge_base_id", kbId);
                    synthetic.put("external_id", kbId);
                    synthetic.put("connection_id", connectionId);
                    knowledgeBases.add(synthetic);
                    LOG.info("CUSTOM mode: using kb_id as external_id: kb_id=" + kbId);
                } else {
                    LOG.warning("Failed to load KB reference from OBS: kb_id=" + kbId);
                }
            }
        }

        Map<String, Object> retrievalParams = new LinkedHashMap<>(KbHttp.mapOf(configs.get("retrievalConfig")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection", connection);
        out.put("knowledge_bases", knowledgeBases);
        out.put("retrieval_params", retrievalParams);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConnection(String connectionId) {
        String cacheKey = "conn:" + connectionId;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) {
            return parseConnection(cached);
        }
        String objectKey = CONNECTION_PATH.formatted(connectionId);
        try {
            String content = KnowledgeBaseConfigProviders.storage().getContent(objectKey);
            Map<String, Object> data = MAPPER.readValue(content, Map.class);
            setCached(cacheKey, data);
            return parseConnection(data);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load connection file from OBS: connection_id=" + connectionId, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadKbReference(String knowledgeBaseId) {
        String cacheKey = "kb:" + knowledgeBaseId;
        Map<String, Object> cached = getCached(cacheKey);
        if (cached != null) {
            return parseKbReference(cached);
        }
        String objectKey = KB_PATH.formatted(knowledgeBaseId);
        try {
            String content = KnowledgeBaseConfigProviders.storage().getContent(objectKey);
            Map<String, Object> data = MAPPER.readValue(content, Map.class);
            setCached(cacheKey, data);
            return parseKbReference(data);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to load KB file from OBS: kb_id=" + knowledgeBaseId, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseConnection(Map<String, Object> connectionData) {
        if (connectionData == null || connectionData.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> paramsDict = new LinkedHashMap<>();
        Object params = connectionData.get("params");
        if (params instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String code = KbHttp.str(m.get("code"));
                String value = KbHttp.str(m.get("value"));
                value = KnowledgeBaseConfigProviders.maybeDecrypt(code, value);
                paramsDict.put(code, value);
            }
        }

        String authorization = KbHttp.str(paramsDict.get("authorization"));
        String authMode = KbHttp.str(paramsDict.get("auth_mode"));
        if (authorization.isBlank()) {
            String user = KbHttp.str(paramsDict.get("user_name"));
            String password = KbHttp.str(paramsDict.get("password"));
            if (!user.isBlank() && !password.isBlank()) {
                authorization = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
                authMode = "BASIC";
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection_id", KbHttp.str(connectionData.get("connectionId")));
        out.put("connector_type", KbHttp.str(connectionData.get("connectorId")));
        out.put("connector_name", KbHttp.str(connectionData.get("connectorName")));
        out.put("knowledge_source", KbHttp.str(connectionData.get("knowledgeSource")));
        out.put("endpoint", KbHttp.str(paramsDict.get("endpoint")));
        out.put("auth_mode", authMode);
        out.put("authorization", authorization);
        out.put("extra_params", paramsDict);
        out.put("used_abilities", connectionData.getOrDefault("usedAbilities", List.of()));
        return out;
    }

    static Map<String, Object> parseKbReference(Map<String, Object> kbData) {
        if (kbData == null || kbData.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("knowledge_base_id", KbHttp.str(kbData.get("knowledgeBaseId")));
        out.put("knowledge_base_name", KbHttp.str(kbData.get("knowledgeBaseName")));
        out.put("type", KbHttp.str(kbData.get("type")));
        out.put("external_id", KbHttp.str(kbData.get("externalId")));
        out.put("status", KbHttp.str(kbData.get("status")));
        out.put("connection_id", KbHttp.str(kbData.get("connectionId")));
        Object tags = kbData.get("tags");
        out.put("tags", tags instanceof List<?> ? tags : List.of());
        return out;
    }

    static void mergeAuthHeaders(Map<String, Object> connection) {
        if (!KbHttp.str(connection.get("authorization")).isBlank()) {
            return;
        }
        String connector = KbHttp.str(connection.get("connector_type")).toLowerCase();
        if (!(connector.startsWith("lakesearch") || "custom".equals(connector) || connector.isBlank())) {
            return;
        }
        String token = KnowledgeRequestContext.authToken();
        if (!token.isBlank()) {
            connection.put("authorization", token);
            if (KbHttp.str(connection.get("auth_mode")).isBlank()) {
                connection.put("auth_mode", "TOKEN");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCached(String key) {
        CachedEntry e = cache.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() - e.ts > CACHE_TTL_MS) {
            cache.remove(key);
            return null;
        }
        return e.value;
    }

    private void setCached(String key, Map<String, Object> value) {
        cache.put(key, new CachedEntry(new LinkedHashMap<>(value), System.currentTimeMillis()));
    }

    private record CachedEntry(Map<String, Object> value, long ts) {}
}
