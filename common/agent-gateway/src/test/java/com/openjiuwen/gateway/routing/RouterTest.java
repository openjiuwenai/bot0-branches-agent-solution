/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.openjiuwen.gateway.direct.FakeAgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

/**
 * Unit tests for {@link Router} create path (FEAT-011 L2 §4 T-S2-1/2/5/6/7 +
 * routing-failure branches). Uses fake RDC + fake runtime; real StickyIndex +
 * DefaultAgentResolver.
 */
class RouterTest {
    private final FakeRdcRouteClient rdc = new FakeRdcRouteClient();
    private final FakeAgentRuntimeClient runtime = new FakeAgentRuntimeClient();
    private final StickyIndex sticky = new StickyIndex();
    private final DefaultAgentResolver defaultAgent = new DefaultAgentResolver("default-agent-1");
    private final Router router = new Router(rdc, runtime, sticky, defaultAgent);

    private static final String ENDPOINT = "http://runtime-1:8000";
    private static final String TASK_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"kind\":\"task\",\"id\":\"task-7\",\"contextId\":\"c1\"}}";

    private static GovernanceContext createCtx(String agentId) {
        GovernanceContext ctx = new GovernanceContext();
        ctx.setTenantId("tenant-1");
        ctx.setAgentId(agentId);
        ctx.setRawBody("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"method\":\"SendMessage\","
                + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]}}}");
        return ctx;
    }

    @Test
    void explicitAgentRoutesToFirstCandidateAndWritesSticky() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1"), new AgentCardRoute("h2")));
        rdc.setResolved(new ResolvedRoute(ENDPOINT));
        runtime.setResponse(TASK_BODY);

        String response = router.routeCreate(createCtx("agent-9"));

        assertThat(response).isEqualTo(TASK_BODY);
        assertThat(rdc.lastAgentId()).isEqualTo("agent-9");
        // picked the first candidate
        // forwarded to the resolved endpoint with the authoritative tenant injected
        assertThat(runtime.lastEndpoint()).isEqualTo(ENDPOINT);
        assertThat(runtime.lastBody()).contains("\"tenantId\":\"tenant-1\"");
        // sticky bound taskId -> first candidate's handle
        assertThat(sticky.find("task-7")).contains("h1");
    }

    @Test
    void multiInstancePicksFirstCandidate() {
        rdc.setCandidates(List.of(new AgentCardRoute("first"), new AgentCardRoute("second")));
        rdc.setResolved(new ResolvedRoute(ENDPOINT));
        runtime.setResponse(TASK_BODY);
        router.routeCreate(createCtx("agent-9"));
        assertThat(sticky.find("task-7")).contains("first");
    }

    @Test
    void noAgentIdFallsBackToDefaultAgent() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1")));
        rdc.setResolved(new ResolvedRoute(ENDPOINT));
        runtime.setResponse(TASK_BODY);
        router.routeCreate(createCtx(null));
        assertThat(rdc.lastAgentId()).isEqualTo("default-agent-1");
    }

    @Test
    void reentryWithExplicitAgentDoesNotUseDefault() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1")));
        rdc.setResolved(new ResolvedRoute(ENDPOINT));
        runtime.setResponse(TASK_BODY);
        router.routeCreate(createCtx("target-agent"));
        assertThat(rdc.lastAgentId()).isEqualTo("target-agent");
    }

    @Test
    void emptyCandidatesReturnsRouteNoCandidates() {
        rdc.setCandidates(List.of());
        GovernanceException ge = (GovernanceException) catchThrowable(() -> router.routeCreate(createCtx("agent-9")));
        assertThat(ge).isNotNull();
        assertThat(ge.code()).isEqualTo("ROUTE_NO_CANDIDATES");
    }

    @Test
    void resolveFailureReturnsRouteResolveFailed() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1")));
        rdc.setResolved(null); // resolve throws
        GovernanceException ge = (GovernanceException) catchThrowable(() -> router.routeCreate(createCtx("agent-9")));
        assertThat(ge).isNotNull();
        assertThat(ge.code()).isEqualTo("ROUTE_RESOLVE_FAILED");
    }

    @Test
    void defaultAgentUnconfiguredIsConfigError() {
        Router unconfigured = new Router(rdc, runtime, sticky, new DefaultAgentResolver(""));
        GovernanceException ge = (GovernanceException) catchThrowable(() -> unconfigured.routeCreate(createCtx(null)));
        assertThat(ge).isNotNull();
        assertThat(ge.code()).isEqualTo("DEFAULT_AGENT_UNCONFIGURED");
    }

    @Test
    void responseWithoutTaskIdWritesNoSticky() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1")));
        rdc.setResolved(new ResolvedRoute(ENDPOINT));
        runtime.setResponse("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"status\":\"accepted\"}}");
        router.routeCreate(createCtx("agent-9"));
        assertThat(sticky.find("task-7")).isEmpty();
    }
}
