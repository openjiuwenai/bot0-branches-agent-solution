/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Test for RootOutputReducerTest.
 *
 * @since 2026-07-27
 */
class RootOutputReducerTest {
    @Test
    void materializesReplaceAppendAndStableArtifactOrder() {
        RootOutputReducer reducer = new RootOutputReducer();

        reducer.accept(root("a", false, text("old")));
        reducer.accept(root("b", false, text("tail")));
        reducer.accept(root("a", false, text("new")));
        reducer.accept(root("a", true, text("!")));

        assertEquals("new!tail", reducer.currentText().orElseThrow());
    }

    @Test
    void emptyReplaceClearsPreviousTextAndTaskSnapshotIsAuthoritative() {
        RootOutputReducer reducer = new RootOutputReducer();
        reducer.accept(root("a", false, text("stale")));
        reducer.accept(root("a", false));

        assertEquals("", reducer.currentText().orElseThrow());

        reducer.replaceWithTaskSnapshot(List.of(root("b", false,
                new ProtocolPart.Data(Map.of("content", "fresh")))));
        assertEquals("fresh", reducer.currentText().orElseThrow());
    }

    @Test
    void ignoresDescendantAndControllerArtifacts() {
        RootOutputReducer reducer = new RootOutputReducer();
        AgentEvent child = new AgentEvent("output", new AgentEvent.AgentRef("agent-b", "task-b"), null, null);
        reducer.accept(new ProtocolArtifact("child", List.of(text("child")), false, true,
                child, true, false));
        reducer.accept(new ProtocolArtifact("controller", List.of(text("control")), false, true,
                null, false, true));
        reducer.accept(root("root", false, text("root")));

        assertEquals("root", reducer.currentText().orElseThrow());
    }

    private static ProtocolArtifact root(String id, boolean append, ProtocolPart... parts) {
        return new ProtocolArtifact(id, List.of(parts), append, true, null, false, false);
    }

    private static ProtocolPart.Text text(String value) {
        return new ProtocolPart.Text(value);
    }
}
