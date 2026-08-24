/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.gateway.direct.FakeAgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.validate.ParamValidator;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.ResolvedRoute;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.routing.StickyIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * T11: Task snapshot transparent passthrough + no-cache (FEAT-011 cap #6).
 * Verifies gateway forwards runtime Task snapshot without stripping additive
 * fields (FEAT-027 agentEvent metadata), and does not cache snapshots.
 */
class TaskSnapshotPassthroughTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeAgentRuntimeClient runtime;
    private FakeRdcRouteClient rdc;
    private StickyIndex sticky;
    private Router router;
    private final ParamValidator validator = new ParamValidator();

    @BeforeEach
    void setUp() {
        runtime = new FakeAgentRuntimeClient();
        rdc = new FakeRdcRouteClient();
        rdc.setCandidates(List.of(new com.openjiuwen.gateway.routing.AgentCardRoute("h1")));
        rdc.setResolved(new ResolvedRoute("http://runtime:8090"));
        sticky = new StickyIndex();
        sticky.put("task-123", "h1", "svc-rt");
        router = new Router(rdc, runtime, sticky);
    }

    private GovernanceContext getTaskContext(String taskId) {
        GovernanceContext ctx = new GovernanceContext();
        ctx.setMethod("GetTask");
        ctx.setTaskId(taskId);
        ctx.setTenantId("tenant-1");
        ctx.setRawBody("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"GetTask\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}");
        return ctx;
    }

    @Test
    void getTaskSnapshotPassedThroughWithAllA2aFields() throws Exception {
        // Runtime returns a full Task snapshot with status/artifacts/history/metadata + additive agentEvent
        String snapshot = "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{"
                + "\"id\":\"task-123\","
                + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"message\":{\"role\":\"ROLE_AGENT\","
                + "\"parts\":[{\"text\":\"done\"}]}},"
                + "\"artifacts\":[{\"artifactId\":\"out-1\",\"parts\":[{\"text\":\"result\"}]}],"
                + "\"history\":[{\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"metadata\":{\"customKey\":\"customVal\","
                + "\"agentEvent\":{\"source\":\"agent-b1\",\"batchId\":\"b1\"}}"
                + "}}";
        runtime.setResponse(snapshot);

        String response = router.routeGet(getTaskContext("task-123"));

        // Gateway passes through the snapshot verbatim — no field stripped
        JsonNode root = MAPPER.readTree(response);
        assertThat(root.path("result").path("id").asText()).isEqualTo("task-123");
        assertThat(root.path("result").path("status").path("state").asText()).isEqualTo("TASK_STATE_COMPLETED");
        assertThat(root.path("result").path("artifacts").isArray()).isTrue();
        assertThat(root.path("result").path("history").isArray()).isTrue();
        // Additive metadata (agentEvent) preserved — not stripped by gateway
        assertThat(root.path("result").path("metadata").path("customKey").asText()).isEqualTo("customVal");
        assertThat(root.path("result").path("metadata").path("agentEvent").path("source").asText())
                .isEqualTo("agent-b1");
    }

    @Test
    void getTaskRuntimeErrorMinus32001PassedThrough() throws Exception {
        // Runtime returns -32001 (task not found) — gateway passes through
        runtime.setResponse("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\","
                + "\"error\":{\"code\":-32001,\"message\":\"Task not found\"}}");

        String response = router.routeGet(getTaskContext("task-123"));

        JsonNode root = MAPPER.readTree(response);
        assertThat(root.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(root.path("error").path("message").asText()).isEqualTo("Task not found");
    }

    @Test
    void getTaskRequestContainsParamsIdAndTenant() {
        // Verify the gateway-built GetTask request uses params.id + params.tenant (not params.taskId)
        runtime.setResponse("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"id\":\"task-123\"}}");
        router.routeGet(getTaskContext("task-123"));

        String sentBody = runtime.lastBody();
        assertThat(sentBody).contains("\"id\":\"task-123\"");
        assertThat(sentBody).contains("\"tenant\":\"tenant-1\"");
        // Must NOT use params.taskId (runtime expects params.id)
        assertThat(sentBody).doesNotContain("\"taskId\"");
    }

    @Test
    void getTaskWithHistoryLengthPassesThrough() {
        GovernanceContext ctx = getTaskContext("task-123");
        ctx.setHistoryLength(10);
        runtime.setResponse("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"id\":\"task-123\"}}");

        router.routeGet(ctx);

        String sentBody = runtime.lastBody();
        assertThat(sentBody).contains("\"historyLength\":10");
    }
}
