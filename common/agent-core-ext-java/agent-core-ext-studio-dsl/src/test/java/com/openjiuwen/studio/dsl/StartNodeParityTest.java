/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.studio.dsl.adapter.control.StartNodeHandler;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;
import com.openjiuwen.studio.dsl.store.InMemoryConversationValsStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * P1 Start parity vs Python {@code jiuwen.extension.workflow_node.start.Start}.
 *
 * @since 2026-08-25
 */
class StartNodeParityTest {
    private InMemoryConversationValsStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryConversationValsStore();
        ConversationValsStores.setDefault(store);
    }

    @AfterEach
    void tearDown() {
        ConversationValsStores.setDefault(null);
        ConversationValsStores.memoryStore().clear();
    }

    @Test
    void validatesRequiredUserFields() {
        AssembledNode node =
                AssembledNode.of(
                        "s",
                        "jiuwen.start",
                        Map.of(
                                "userFields",
                                Map.of(
                                        "inputs",
                                        List.of(Map.of("id", "name", "required", true, "type", "string")))));
        var exec = new StartNodeHandler().create(node, NodeBuildContext.defaults("wf-1"));
        assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Missing required variables");
    }

    @Test
    void fillsDefaultsAndBuildsMemoryFromPreDefinedSessionVars() {
        AssembledNode node =
                AssembledNode.of(
                        "s",
                        "jiuwen.start",
                        Map.of(
                                "userFields",
                                Map.of(
                                        "inputs",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "greeting",
                                                        "required",
                                                        false,
                                                        "default_value",
                                                        "hi",
                                                        "type",
                                                        "string"))),
                                "preDefinedFields",
                                Map.of(
                                        "inputs",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "memory",
                                                        "type",
                                                        "object",
                                                        "storage_method",
                                                        "assignment",
                                                        "aging_level",
                                                        "session",
                                                        "schema",
                                                        List.of(
                                                                Map.of(
                                                                        "id",
                                                                        "topic",
                                                                        "type",
                                                                        "string",
                                                                        "default_value",
                                                                        "t0",
                                                                        "storage_method",
                                                                        "assignment",
                                                                        "aging_level",
                                                                        "session")))))));

        var exec = new StartNodeHandler().create(node, NodeBuildContext.defaults("wf-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>)
                        exec.invoke(
                                Map.of(
                                        "userFields",
                                        Map.of(
                                                "global_variables",
                                                Map.of("conversationId", "c1", "appId", "wf-1"))),
                                null,
                                null);

        assertThat(out.get("userFields")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("greeting")).isEqualTo("hi");
        assertThat(out.get("memory")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) out.get("memory");
        assertThat(memory.get("topic")).isEqualTo("t0");
        assertThat(out.get("systemFields")).isInstanceOf(Map.class);
    }

    @Test
    void redisRoundTrip_mergesStoredSessionVars() {
        store.setMap("global.vals.wf-1.c1", Map.of("topic", "from-redis"), 1000);
        AssembledNode node =
                AssembledNode.of(
                        "s",
                        "jiuwen.start",
                        Map.of(
                                "preDefinedFields",
                                Map.of(
                                        "inputs",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "memory",
                                                        "storage_method",
                                                        "assignment",
                                                        "aging_level",
                                                        "session",
                                                        "schema",
                                                        List.of(
                                                                Map.of(
                                                                        "id",
                                                                        "topic",
                                                                        "type",
                                                                        "string",
                                                                        "default_value",
                                                                        "t0",
                                                                        "storage_method",
                                                                        "assignment",
                                                                        "aging_level",
                                                                        "session")))))));

        var exec = new StartNodeHandler().create(node, NodeBuildContext.defaults("wf-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out =
                (Map<String, Object>)
                        exec.invoke(
                                Map.of(
                                        "userFields",
                                        Map.of(
                                                "global_variables",
                                                Map.of("conversationId", "c1", "appId", "wf-1"))),
                                null,
                                null);
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) out.get("memory");
        assertThat(memory.get("topic")).isEqualTo("from-redis");
    }

    @Test
    void userInputMemory_persistsToStore() {
        AssembledNode node =
                AssembledNode.of(
                        "s",
                        "jiuwen.start",
                        Map.of(
                                "preDefinedFields",
                                Map.of(
                                        "inputs",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "memory",
                                                        "storage_method",
                                                        "assignment",
                                                        "aging_level",
                                                        "session",
                                                        "schema",
                                                        List.of(
                                                                Map.of(
                                                                        "id",
                                                                        "topic",
                                                                        "type",
                                                                        "string",
                                                                        "default_value",
                                                                        "t0",
                                                                        "storage_method",
                                                                        "assignment",
                                                                        "aging_level",
                                                                        "session")))))));

        var exec = new StartNodeHandler().create(node, NodeBuildContext.defaults("wf-1"));
        exec.invoke(
                Map.of(
                        "userFields",
                        Map.of(
                                "global_variables",
                                Map.of("conversationId", "c1", "appId", "wf-1", "topic", "user-set"))),
                null,
                null);

        assertThat(store.getMap("global.vals.wf-1.c1").get("topic")).isEqualTo("user-set");
    }

    @Test
    void redisColdStart_doesNotPersistUndefinedDefaults() {
        AssembledNode node =
                AssembledNode.of(
                        "s",
                        "jiuwen.start",
                        Map.of(
                                "preDefinedFields",
                                Map.of(
                                        "inputs",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        "memory",
                                                        "storage_method",
                                                        "assignment",
                                                        "aging_level",
                                                        "session",
                                                        "schema",
                                                        List.of(
                                                                Map.of(
                                                                        "id",
                                                                        "topic",
                                                                        "type",
                                                                        "string",
                                                                        "default_value",
                                                                        "t0",
                                                                        "storage_method",
                                                                        "assignment",
                                                                        "aging_level",
                                                                        "session"),
                                                                Map.of(
                                                                        "id",
                                                                        "other",
                                                                        "type",
                                                                        "string",
                                                                        "default_value",
                                                                        "o0",
                                                                        "storage_method",
                                                                        "assignment",
                                                                        "aging_level",
                                                                        "session")))))));

        var exec = new StartNodeHandler().create(node, NodeBuildContext.defaults("wf-1"));
        exec.invoke(
                Map.of(
                        "userFields",
                        Map.of(
                                "global_variables",
                                Map.of("conversationId", "c1", "appId", "wf-1", "topic", "user-set"))),
                null,
                null);

        Map<String, Object> stored = store.getMap("global.vals.wf-1.c1");
        assertThat(stored.get("topic")).isEqualTo("user-set");
        assertThat(stored).doesNotContainKey("other");
    }
}
