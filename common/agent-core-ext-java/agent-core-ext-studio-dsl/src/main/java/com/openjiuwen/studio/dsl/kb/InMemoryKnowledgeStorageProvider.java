/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.studio.dsl.contract.KnowledgeStorageProvider;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * In-memory OBS stub for tests (Python storage mock).
 *
 * @since 2026-08-26
 */

public final class InMemoryKnowledgeStorageProvider implements KnowledgeStorageProvider {
    private final Map<String, String> objects = new ConcurrentHashMap<>();

    /**
     * put.
     *
     * @param objectKey key
     * @param content JSON text
     */

    public void put(String objectKey, String content) {
        objects.put(objectKey, content);
    }

    /**
     * getContent.
     *
     * @param objectKey objectKey
     * @return result
     * @since 0.1.0
     */

    @Override
    public String getContent(String objectKey) {
        String v = objects.get(objectKey);
        if (v == null) {
            throw new IllegalStateException("object not found: " + objectKey);
        }
        return v;
    }
}
