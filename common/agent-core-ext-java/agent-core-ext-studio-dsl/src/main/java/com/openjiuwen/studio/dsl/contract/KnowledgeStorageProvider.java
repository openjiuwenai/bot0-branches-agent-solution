/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

/**
 * OBS / object storage content loader for knowledge-base IR files
 * (Python {@code storage.get_storage_provider().get_content}).
 *
 * @since 2026-08-26
 */
@FunctionalInterface
public interface KnowledgeStorageProvider {
    /**
     * getContent.
     *
     * @param objectKey object key (e.g. {@code kb-connection/ir/connection/{id}.json})
     * @return UTF-8 JSON text
     * @throws Exception when object is missing or unreadable
     */
    String getContent(String objectKey) throws Exception;
}
