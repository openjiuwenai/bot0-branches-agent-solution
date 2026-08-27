/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import java.util.Map;

/**
 * Knowledge-base connection + KB reference loader
 * (Python {@code KnowledgeBaseConfigProvider.get_kb_config}).
 *
 * @since 2026-08-26
 */

@FunctionalInterface
public interface KnowledgeBaseConfigProvider {

    /**
     * getKbConfig.
     *
     * @param nodeConfigs IR node configs (connectionId, knowledgeBaseIds, retrievalConfig, …)
     * @return map with keys {@code connection}, {@code knowledge_bases}, {@code retrieval_params}
     */

    Map<String, Object> getKbConfig(Map<String, Object> nodeConfigs);
}
