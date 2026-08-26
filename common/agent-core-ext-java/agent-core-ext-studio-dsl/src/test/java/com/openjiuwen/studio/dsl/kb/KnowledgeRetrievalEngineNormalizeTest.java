/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Normalize + Redis cache parity (Python {@code normalize_results_like_java}).
 *
 * @since 2026-08-26
 */
class KnowledgeRetrievalEngineNormalizeTest {

    @AfterEach
    void tearDown() {
        KnowledgeRetrievalCacheStore.clearMemory();
        KnowledgeRetrievalCacheStore.setJedis(null);
    }

    @Test
    void normalize_cachesVirtualFileId() {
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine("k1", Map.of());
        KBSearchResult item = new KBSearchResult()
                .setText("hello")
                .setScore(0.9)
                .setSource("external-1")
                .setKnowledgeBaseId("external-1")
                .setFileId("real-file-1")
                .setType("doc");
        List<KBSearchResult> normalized =
                engine.normalizeResults(
                        List.of(item),
                        List.of(Map.of(
                                "knowledge_base_id", "kb-1",
                                "external_id", "external-1",
                                "type", "INTERNAL")),
                        Map.of("retrieveImage", false));
        assertThat(normalized.get(0).knowledgeBaseId()).isEqualTo("kb-1");
        assertThat(normalized.get(0).fileId()).isNotEqualTo("real-file-1");
        String cacheKey = KnowledgeRetrievalCacheStore.FILE_PREFIX + normalized.get(0).fileId();
        assertThat(KnowledgeRetrievalCacheStore.get(cacheKey)).isEqualTo("kb-1,doc,real-file-1");
    }

    @Test
    void normalize_replacesImageAndCachesAccessKey() {
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine("k1", Map.of());
        KBSearchResult item = new KBSearchResult()
                .setText("before {img-abc123} after")
                .setScore(0.9)
                .setSource("external-1")
                .setKnowledgeBaseId("external-1")
                .setType("doc");
        List<KBSearchResult> normalized =
                engine.normalizeResults(
                        List.of(item),
                        List.of(Map.of("knowledge_base_id", "kb-1", "external_id", "external-1")),
                        Map.of("retrieveImage", true));
        assertThat(normalized.get(0).text()).doesNotContain("{img-abc123}");
        assertThat(normalized.get(0).text()).contains("https://agent_arts_knowledge_img_url/");
        String imageKey =
                KnowledgeRetrievalCacheStore.memorySnapshot().keySet().stream()
                        .filter(k -> k.startsWith(KnowledgeRetrievalCacheStore.IMAGE_PREFIX))
                        .findFirst()
                        .orElseThrow();
        assertThat(KnowledgeRetrievalCacheStore.get(imageKey)).isEqualTo("kb-1,img-abc123");
    }
}
