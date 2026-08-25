/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.List;
import java.util.Map;

/**
 * KB HTTP adapter (Python {@code KBServiceAdapter}).
 *
 * @since 2026-08-25
 */
public interface KBServiceAdapter {
    /**
     * search.
     *
     * @param query query
     * @param connectionConfig connectionConfig
     * @param knowledgeBases knowledgeBases
     * @param retrievalParams retrievalParams
     * @return hits
     */
    List<KBSearchResult> search(
            String query,
            Map<String, Object> connectionConfig,
            List<Map<String, Object>> knowledgeBases,
            Map<String, Object> retrievalParams);
}
