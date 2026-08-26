/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowstart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.store.InMemoryConversationValsStore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit parity for Python {@code start.py} helpers / Redis cold-start / request fill.
 *
 * @since 2026-08-26
 */
class FlowStartEngineTest {

    @Test
    void transformType_booleanMatchesPythonBool() {
        assertThat(FlowStartAssignmentSupport.transformType("boolean", "false")).isEqualTo(true);
        assertThat(FlowStartAssignmentSupport.transformType("boolean", "")).isNull();
        assertThat(FlowStartAssignmentSupport.transformType("boolean", Boolean.FALSE)).isEqualTo(false);
    }

    @Test
    void redisColdStart_saveDoesNotInventDefaults() {
        InMemoryConversationValsStore store = new InMemoryConversationValsStore();
        FlowStartEngine engine =
                new FlowStartEngine(
                        "s",
                        FlowStartConfig.fromNodeConfigs(Map.of(), "s"),
                        "wf-1",
                        store);
        Map<String, Object> defs = Map.of("topic", "t0", "other", "o0");
        engine.saveRedisSessionVars("wf-1", "c1", defs, Map.of("topic", "user-set"));
        Map<String, Object> stored = store.getMap("global.vals.wf-1.c1");
        assertThat(stored).containsEntry("topic", "user-set");
        assertThat(stored).doesNotContainKey("other");
    }

    @Test
    void assembleOutput_fillsFromRequestKey() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("_request")).thenReturn(Map.of("city", "SZ"));
        FlowStartEngine engine =
                new FlowStartEngine(
                        "s",
                        FlowStartConfig.fromNodeConfigs(Map.of(), "s"),
                        "wf",
                        new InMemoryConversationValsStore());
        Map<String, Object> out = engine.assembleOutput(Map.of("query", "q"), Map.of(), session);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("city")).isEqualTo("SZ");
        assertThat(uf.get("query")).isEqualTo("q");
    }

    @Test
    void assembleOutput_dialogueHistoryFromIoState() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("io_state"))
                .thenReturn(Map.of("dialogueHistory", List.of(Map.of("role", "assistant", "content", "hi"))));
        FlowStartEngine engine =
                new FlowStartEngine(
                        "s",
                        FlowStartConfig.fromNodeConfigs(Map.of(), "s"),
                        "wf",
                        new InMemoryConversationValsStore());
        Map<String, Object> out = engine.assembleOutput(Map.of("query", "q"), Map.of(), session);
        @SuppressWarnings("unchecked")
        Map<String, Object> sf = (Map<String, Object>) out.get("systemFields");
        assertThat(sf.get("dialogueHistory")).isInstanceOf(List.class);
        assertThat((List<?>) sf.get("dialogueHistory")).hasSize(1);
    }
}
