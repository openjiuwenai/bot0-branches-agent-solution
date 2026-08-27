/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.studio.dsl.contract.KnowledgeBaseConfigProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Env-var fallback KB config (Python {@code EnvVarKBConfigProvider}).
 *
 * @since 2026-08-26
 */

public final class EnvVarKnowledgeBaseConfigProvider implements KnowledgeBaseConfigProvider {
    @Override
    public Map<String, Object> getKbConfig(Map<String, Object> nodeConfigs) {
        Map<String, Object> configs = nodeConfigs == null ? Map.of() : nodeConfigs;
        Map<String, Object> retrievalConfig = KbHttp.mapOf(configs.get("retrievalConfig"));

        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("connection_id", "env_default");
        connection.put("connector_type", env("KB_CONNECTOR_TYPE", "LakeSearch"));
        connection.put("endpoint", env("KB_ENDPOINT", ""));
        connection.put("auth_mode", env("KB_AUTH_MODE", "BASIC"));
        connection.put("authorization", env("KB_AUTHORIZATION", ""));

        List<Map<String, Object>> knowledgeBases = new ArrayList<>();
        Object kbIds = configs.get("knowledgeBaseIds");
        if (kbIds instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                Map<String, Object> kb = new LinkedHashMap<>();
                kb.put("knowledge_base_id", String.valueOf(item));
                kb.put("external_id", env("KB_EXTERNAL_ID", String.valueOf(item)));
                kb.put("connection_id", "env_default");
                knowledgeBases.add(kb);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection", connection);
        out.put("knowledge_bases", knowledgeBases);
        out.put("retrieval_params", new LinkedHashMap<>(retrievalConfig));
        return out;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
