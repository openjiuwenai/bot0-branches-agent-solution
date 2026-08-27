/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Parity helpers for Python {@code end.py} convert / template markers / user_fields.
 *
 * @since 2026-08-26
 */

class FlowEndEngineTest {
    @Test
    void convertSimple_skipsIterator() {
        Iterator<String> it = List.of("a").iterator();
        assertThat(FlowEndEngine.convertSimple(it, "string")).isSameAs(it);
    }

    @Test
    void convertSimple_booleanMatchesPythonBool() {
        assertThat(FlowEndEngine.convertSimple("false", "boolean")).isEqualTo(true);
        assertThat(FlowEndEngine.convertSimple("", "boolean")).isEqualTo(false);
        assertThat(FlowEndEngine.convertSimple(0, "boolean")).isEqualTo(false);
        assertThat(FlowEndEngine.convertSimple(Boolean.FALSE, "boolean")).isEqualTo(false);
    }

    @Test
    void convertSimple_boolToInteger() {
        assertThat(FlowEndEngine.convertSimple(true, "integer")).isEqualTo(1);
        assertThat(FlowEndEngine.convertSimple(false, "integer")).isEqualTo(0);
    }

    @Test
    void templateMarkers_matchSplitEmptySegments() {
        assertThat(FlowEndEngine.shouldEmitStartMarker("{{name}}!")).isTrue();
        assertThat(FlowEndEngine.shouldEmitEndMarker("Hi {{name}}")).isTrue();
        assertThat(FlowEndEngine.shouldEmitStartMarker("Hi {{name}}!")).isFalse();
        assertThat(FlowEndEngine.shouldEmitEndMarker("Hi {{name}}!")).isFalse();
    }

    @Test
    void queryOf_readsSessionGlobalStateOnly() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("query")).thenReturn("hello");
        assertThat(FlowEndEngine.queryOf(session)).isEqualTo("hello");
        assertThat(FlowEndEngine.queryOf(null)).isEmpty();
        when(session.getGlobalState("query")).thenReturn(null);
        assertThat(FlowEndEngine.queryOf(session)).isEmpty();
    }

    @Test
    void buildUserFields_injectsQueryAndTerminal() {
        Map<String, Object> uf =
                FlowEndEngine.buildUserFields(Map.of("x", 1), Map.of("answer", "a"), "q1", false);
        assertThat(uf.get("query")).isEqualTo("q1");
        assertThat(uf.get("x")).isEqualTo(1);
        assertThat(uf.get("answer")).isEqualTo("a");
        assertThat(uf.get("__terminal__")).isEqualTo(true);
    }
}
