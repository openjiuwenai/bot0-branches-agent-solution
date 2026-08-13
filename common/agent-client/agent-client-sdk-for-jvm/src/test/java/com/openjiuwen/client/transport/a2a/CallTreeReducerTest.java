/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.calltree.CallTreeNode;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;
import com.openjiuwen.client.api.calltree.Completeness;
import com.openjiuwen.client.api.calltree.TextPartSnapshot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

class CallTreeReducerTest {
    @Test
    void replaysOutputAndChildEdgeWhenParentsArriveLater() {
        CallTreeReducer reducer = new CallTreeReducer(InvocationMode.STREAMING);
        reducer.bindRoot("root");

        reducer.accept(output("c-output", "agent-c", "task-c", "C first"));
        reducer.accept(delegation("b-to-c", "agent-b", "task-b", "agent-c", "task-c"));
        reducer.accept(delegation("root-to-b", "agent-a", "root", "agent-b", "task-b"));

        CallTreeSnapshot tree = reducer.current().orElseThrow();
        CallTreeNode child = tree.root().children().get(0).children().get(0);
        assertEquals("task-c", child.key().taskId());
        assertEquals("C first", ((TextPartSnapshot) child.artifacts().get(0).parts().get(0)).text());
        assertEquals(Completeness.LIVE, tree.completeness());
    }

    @Test
    void keepsFirstArtifactOwnerAndDegradesOnCrossNodeConflict() {
        CallTreeReducer reducer = twoChildren();
        reducer.accept(output("shared", "agent-b1", "task-b1", "owner"));
        reducer.accept(output("shared", "agent-b2", "task-b2", "conflict"));

        CallTreeSnapshot tree = reducer.current().orElseThrow();
        assertEquals(Completeness.DEGRADED, tree.completeness());
        assertEquals(1, tree.root().children().get(0).artifacts().size());
        assertTrue(tree.root().children().get(1).artifacts().isEmpty());
    }

    @Test
    void orphanFrameKeepsFirstArtifactOwnership() {
        CallTreeReducer reducer = twoChildren();
        reducer.accept(output("shared", "agent-c", "task-c", "orphan owner"));
        reducer.accept(output("shared", "agent-b1", "task-b1", "later conflict"));
        reducer.accept(delegation("b2-to-c", "agent-b2", "task-b2", "agent-c", "task-c"));

        CallTreeSnapshot tree = reducer.current().orElseThrow();
        assertEquals(Completeness.DEGRADED, tree.completeness());
        assertTrue(tree.root().children().get(0).artifacts().isEmpty());
        CallTreeNode child = tree.root().children().get(1).children().get(0);
        assertEquals("orphan owner", ((TextPartSnapshot) child.artifacts().get(0).parts().get(0)).text());
    }

    @Test
    void truncatesOversizedArtifactOnUtf8BoundaryAndDegradesOnlyTree() {
        CallTreeReducer reducer = new CallTreeReducer(InvocationMode.STREAMING);
        reducer.bindRoot("root");
        String oversized = "\u4e2d".repeat(800_000);

        reducer.accept(rootOutput("large", oversized));

        CallTreeSnapshot tree = reducer.current().orElseThrow();
        String retained = ((TextPartSnapshot) tree.root().artifacts().get(0).parts().get(0)).text();
        assertEquals(Completeness.DEGRADED, tree.completeness());
        assertTrue(retained.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024);
        assertTrue(oversized.startsWith(retained));
    }

    @Test
    void unresolvedOrphanDegradesFinalSnapshot() {
        CallTreeReducer reducer = new CallTreeReducer(InvocationMode.STREAMING);
        reducer.bindRoot("root");
        reducer.accept(output("orphan", "agent-missing", "task-missing", "unassigned"));

        reducer.close();

        assertEquals(Completeness.DEGRADED, reducer.current().orElseThrow().completeness());
    }

    private static CallTreeReducer twoChildren() {
        CallTreeReducer reducer = new CallTreeReducer(InvocationMode.STREAMING);
        reducer.bindRoot("root");
        reducer.accept(delegation("d1", "agent-a", "root", "agent-b1", "task-b1"));
        reducer.accept(delegation("d2", "agent-a", "root", "agent-b2", "task-b2"));
        return reducer;
    }

    private static ProtocolArtifact delegation(String artifactId, String sourceAgent, String sourceTask,
            String targetAgent, String targetTask) {
        return artifact(artifactId, "delegate", new AgentEvent("delegation",
                new AgentEvent.AgentRef(sourceAgent, sourceTask),
                new AgentEvent.AgentRef(targetAgent, targetTask), null));
    }

    private static ProtocolArtifact output(String artifactId, String agentId, String taskId, String text) {
        return artifact(artifactId, text, new AgentEvent("output",
                new AgentEvent.AgentRef(agentId, taskId), null, null));
    }

    private static ProtocolArtifact rootOutput(String artifactId, String text) {
        return new ProtocolArtifact(artifactId, List.of(new ProtocolPart.Text(text)), false,
                true, null, false, false);
    }

    private static ProtocolArtifact artifact(String artifactId, String text, AgentEvent event) {
        return new ProtocolArtifact(artifactId, List.of(new ProtocolPart.Text(text)), false,
                true, event, true, false);
    }
}
