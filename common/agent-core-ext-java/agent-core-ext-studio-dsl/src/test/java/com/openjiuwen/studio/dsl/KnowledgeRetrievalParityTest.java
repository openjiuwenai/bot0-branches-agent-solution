/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.kb.GeneralKBAdapter;
import com.openjiuwen.studio.dsl.kb.KBAdapterFactory;
import com.openjiuwen.studio.dsl.kb.KBSearchResult;
import com.openjiuwen.studio.dsl.kb.KBServiceAdapter;
import com.openjiuwen.studio.dsl.kb.KnowledgeRetrievalEngine;
import com.openjiuwen.studio.dsl.kb.LakeSearchAdapter;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P4 KnowledgeRetrieval + kb adapter parity tests.
 *
 * @since 2026-08-25
 */
class KnowledgeRetrievalParityTest {

    @AfterEach
    void resetFactory() {
        // restore General registration (tests may override)
        KBAdapterFactory.register("General", GeneralKBAdapter::new);
        KBAdapterFactory.register("FakeKB", () -> (q, c, k, p) -> List.of());
    }

    @Test
    void factory_supports_builtin_types() {
        assertThat(KBAdapterFactory.supportedTypes()).contains("LakeSearch", "General", "Ragflow", "KooSearch");
        assertThat(KBAdapterFactory.create("general")).isInstanceOf(GeneralKBAdapter.class);
        assertThat(KBAdapterFactory.create("lakesearch")).isInstanceOf(LakeSearchAdapter.class);
    }

    @Test
    void general_parse_response() {
        Map<String, Object> resp = Map.of(
                "search_result_list",
                List.of(Map.of("content", "hello", "score", 0.9, "knowledge_base_id", "kb1", "title", "t1")));
        List<KBSearchResult> hits = GeneralKBAdapter.parseResponse(resp);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).text()).isEqualTo("hello");
        assertThat(hits.get(0).score()).isEqualTo(0.9);
        assertThat(hits.get(0).documentName()).isEqualTo("t1");
    }

    @Test
    void lakesearch_parse_response() {
        Map<String, Object> resp = Map.of(
                "doc_list",
                List.of(Map.of("content", "lake", "score", 0.8, "repo_id", "r1", "title", "doc")));
        List<KBSearchResult> hits = LakeSearchAdapter.parseResponse(resp, "fallback");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).knowledgeBaseId()).isEqualTo("r1");
    }

    @Test
    void mockDocuments_still_works() {
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine(
                "k1", Map.of("mockDocuments", List.of(Map.of("text", "a"))));
        Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of("query", "q")), null);
        assertThat(out.get("documents")).isInstanceOf(List.class);
    }

    @Test
    void empty_query_fails() {
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine(
                "k1",
                Map.of(
                        "kbConfig",
                        Map.of(
                                "connection", Map.of("connector_type", "FakeKB", "endpoint", "http://x"),
                                "knowledge_bases", List.of(Map.of("external_id", "e1")),
                                "retrieval_params", Map.of("topK", 3))));
        assertThatThrownBy(() -> engine.invoke(Map.of("userFields", Map.of()), null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("Query must be a non-empty string");
    }

    @Test
    void engine_uses_registered_adapter_and_faq_fallback() {
        AtomicInteger calls = new AtomicInteger();
        KBAdapterFactory.register("FakeKB", () -> new KBServiceAdapter() {
            @Override
            public List<KBSearchResult> search(
                    String query,
                    Map<String, Object> connectionConfig,
                    List<Map<String, Object>> knowledgeBases,
                    Map<String, Object> retrievalParams) {
                calls.incrementAndGet();
                String mode = String.valueOf(retrievalParams.getOrDefault("searchMode", "doc"));
                if ("faq".equalsIgnoreCase(mode)) {
                    return List.of(); // empty → fallback
                }
                return List.of(new KBSearchResult().setText("doc-hit").setScore(0.95).setSource("e1"));
            }
        });
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine(
                "k1",
                Map.of(
                        "kbConfig",
                        Map.of(
                                "connection",
                                Map.of("connector_type", "FakeKB", "endpoint", "http://x"),
                                "knowledge_bases",
                                List.of(Map.of(
                                        "knowledge_base_id",
                                        "kb1",
                                        "external_id",
                                        "e1",
                                        "status",
                                        "OPEN")),
                                "retrieval_params",
                                Map.of("topK", 5, "needExtrasFaqSearch", true))));
        Map<String, Object> out = engine.invoke(Map.of("userFields", Map.of("query", "hello")), null);
        assertThat(calls.get()).isEqualTo(2); // faq then doc
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) out.get("output_list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("text")).isEqualTo("doc-hit");
        assertThat(list.get(0).get("type")).isEqualTo("doc");
    }

    @Test
    void closed_kb_filtered() {
        AtomicInteger seen = new AtomicInteger();
        KBAdapterFactory.register("FakeKB", () -> (q, c, kbs, p) -> {
            seen.set(kbs.size());
            return new ArrayList<>();
        });
        KnowledgeRetrievalEngine engine = new KnowledgeRetrievalEngine(
                "k1",
                Map.of(
                        "kbConfig",
                        Map.of(
                                "connection", Map.of("connector_type", "FakeKB"),
                                "knowledge_bases",
                                List.of(
                                        Map.of("external_id", "a", "status", "CLOSE", "knowledge_base_id", "1"),
                                        Map.of("external_id", "b", "status", "OPEN", "knowledge_base_id", "2")),
                                "retrieval_params",
                                Map.of("topK", 3))));
        engine.invoke(Map.of("userFields", Map.of("query", "q")), null);
        assertThat(seen.get()).isEqualTo(1);
    }

    @Test
    void handler_via_registry() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "k1",
                        "jiuwen.knowledgeRetrieval",
                        Map.of("mockDocuments", List.of(Map.of("text", "x")))),
                NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("query", "q")), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("documents")).isInstanceOf(List.class);
    }
}
