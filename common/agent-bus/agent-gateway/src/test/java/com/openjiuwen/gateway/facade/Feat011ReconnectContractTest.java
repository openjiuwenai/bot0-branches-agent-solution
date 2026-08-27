/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openjiuwen.gateway.direct.FakeAgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceErrorHandler;
import com.openjiuwen.gateway.governance.auth.AuthRule;
import com.openjiuwen.gateway.governance.auth.CredentialDirectory;
import com.openjiuwen.gateway.governance.auth.Principal;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.governance.tenant.TenantResolver;
import com.openjiuwen.gateway.governance.validate.ParamValidator;
import com.openjiuwen.gateway.obs.AuditEvent;
import com.openjiuwen.gateway.obs.AuditSink;
import com.openjiuwen.gateway.obs.GovernanceAuditor;
import com.openjiuwen.gateway.path.PathSelector;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.ResolvedRoute;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.routing.StickyIndex;
import com.openjiuwen.gateway.sse.SseBridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

/**
 * FEAT-011 §8.1 #1/#8 cross-tenant isolation contract test. The Gateway's sticky owner index is a
 * {@code (tenantId, taskId)} composite key; a cross-tenant GetTask / SubscribeToTask MUST miss at the
 * Gateway layer (no RDC resolve, no runtime call) and surface TaskNotFound — never another tenant's
 * Task snapshot or event stream. Isolation is discharged at the Gateway, NOT delegated to downstream:
 * the fakes here ({@link FakeRdcRouteClient} / {@link FakeAgentRuntimeClient}) do NOT tenant-check,
 * so a Gateway-layer leak would be directly observable (a real RDC would catch it via
 * {@code TenantIsolationViolationException}, but §8.1 #8 forbids relying on that).
 *
 * <p>Reproduction: {@code mvn -o test -Dtest=Feat011ReconnectContractTest} (agent-gateway module).
 *
 * @since 2026-08-27
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, TenantResolver.class, ParamValidator.class, IdempotencyRule.class,
        GovernanceAuditor.class, GovernanceErrorHandler.class, Router.class, StickyIndex.class,
        SseBridge.class, PathSelector.class})
class Feat011ReconnectContractTest {
    private static final String CREATE_TASK_X =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"agent-9\"}}}";
    private static final String GETTASK_TASK_X =
            "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"GetTask\",\"params\":{\"id\":\"task-x\"}}";
    private static final String SUBSCRIBE_TASK_X =
            "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"SubscribeToTask\",\"params\":{\"id\":\"task-x\"}}";
    /** Create response carrying the runtime taskId the Gateway binds into the sticky index. */
    private static final String CREATE_RESPONSE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-x\"}}";
    /** A snapshot that would leak if the Gateway forwarded a cross-tenant GetTask to runtime. */
    private static final String TASK_SNAPSHOT =
            "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"id\":\"task-x\","
                    + "\"status\":{\"state\":\"TASK_STATE_RUNNING\"},\"artifacts\":[],\"history\":[],"
                    + "\"metadata\":{\"ownerTenant\":\"tenant-A\"}}}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IdempotencyRule idempotencyRule;
    @Autowired
    private CapturingAuditSink auditSink;
    @Autowired
    private FakeRdcRouteClient rdc;
    @Autowired
    private FakeAgentRuntimeClient runtime;
    @Autowired
    private StickyIndex sticky;

    @TestConfiguration
    static class TestConfig {
        @Bean
        CredentialDirectory credentialDirectory() {
            // two tenants bound by credential (self-reported tenant is discarded by TenantResolver)
            return token -> switch (token) {
                case "token-a" -> Optional.of(new Principal("principal-a", "tenant-A"));
                case "token-b" -> Optional.of(new Principal("principal-b", "tenant-B"));
                default -> Optional.empty();
            };
        }

        @Bean
        CapturingAuditSink auditSink() {
            return new CapturingAuditSink();
        }

        @Bean
        FakeRdcRouteClient rdcRouteClient() {
            return new FakeRdcRouteClient();
        }

        @Bean
        FakeAgentRuntimeClient agentRuntimeClient() {
            return new FakeAgentRuntimeClient();
        }
    }

    static class CapturingAuditSink implements AuditSink {
        final List<AuditEvent> events = new java.util.ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    void resetState() {
        idempotencyRule.clear();
        auditSink.events.clear();
        sticky.clear();
        rdc.setCandidates(List.of(new AgentCardRoute("h1")));
        rdc.setResolved(new ResolvedRoute("http://rt-A:8000"));
        runtime.reset();
        rdc.reset();
    }

    /**
     * tenant-A creates task-x; the Gateway binds the sticky owner {@code (tenant-A, task-x)}.
     * Leaves runtime.reset() done so a subsequent cross-tenant op can assert "no runtime call".
     */
    private void tenantACreatesTaskX() throws Exception {
        runtime.setResponse(CREATE_RESPONSE);
        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer token-a").content(CREATE_TASK_X))
                .andExpect(status().isOk());
        assertThat(runtime.lastEndpoint()).as("create must forward to the owning runtime").isEqualTo("http://rt-A:8000");
        runtime.reset(); // clear so a later cross-tenant op can assert "no runtime call"
    }

    /**
     * §8.1 #8 / T03: tenant-B GetTask(task-x) [owned by tenant-A] MUST miss at the Gateway sticky
     * index (composite key), return TaskNotFound, and NOT call runtime — no snapshot leaks.
     */
    @Test
    void getTaskMustNotLeakAcrossTenants() throws Exception {
        tenantACreatesTaskX();
        runtime.setResponse(TASK_SNAPSHOT); // would leak if the Gateway forwarded cross-tenant
        MvcResult res = mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer token-b").content(GETTASK_TASK_X))
                .andReturn();
        assertThat(runtime.lastEndpoint())
                .as("runtime must NOT be called for a cross-tenant GetTask (Gateway-layer isolation)")
                .isNull();
        assertThat(res.getResponse().getContentAsString())
                .as("cross-tenant GetTask must not return the other tenant's Task snapshot")
                .doesNotContain("tenant-A", "ownerTenant", "TASK_STATE_RUNNING");
    }

    /**
     * §8.1 #8 / T04: tenant-B SubscribeToTask(task-x) [owned by tenant-A] MUST miss at the Gateway
     * sticky index and NOT open an SSE to the owning runtime — no cross-tenant event stream.
     */
    @Test
    void subscribeToTaskMustNotLeakAcrossTenants() throws Exception {
        tenantACreatesTaskX();
        runtime.setFrames(List.of("data:tenant-A-event-leak\n\n"));
        MvcResult res = mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer token-b")
                .header("Accept", "text/event-stream").content(SUBSCRIBE_TASK_X))
                .andReturn();
        assertThat(runtime.lastEndpoint())
                .as("runtime must NOT be called for a cross-tenant SubscribeToTask")
                .isNull();
        assertThat(res.getResponse().getContentAsString())
                .as("cross-tenant SubscribeToTask must not stream another tenant's events")
                .doesNotContain("tenant-A-event-leak");
    }

    /**
     * Regression: same-tenant GetTask still hits the sticky binding and forwards to the owning
     * runtime — the composite key does not break the normal path.
     */
    @Test
    void getTaskSameTenantHitsStickyAndForwardsToRuntime() throws Exception {
        tenantACreatesTaskX();
        runtime.setResponse(TASK_SNAPSHOT);
        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer token-a").content(GETTASK_TASK_X))
                .andExpect(status().isOk());
        assertThat(runtime.lastEndpoint())
                .as("same-tenant GetTask must forward to the owning runtime")
                .isEqualTo("http://rt-A:8000");
    }

    /**
     * §7.3 / T05: a sticky HIT but resolve failure (RDC cannot resolve the route handle) MUST
     * surface as an A-class 503 + flat {@code GatewayError{code=ROUTE_RESOLVE_FAILED}} via the
     * global handler — NOT swallowed as a 200 + bare message. The error body must carry the
     * stable code (and a traceId), so the client can retry / fall back to GetTask.
     */
    @Test
    void subscribeToTaskResolveFailureMustReturn503() throws Exception {
        tenantACreatesTaskX();
        rdc.setResolved(null); // resolveRouteHandle throws RouteResolutionException → A-class 503

        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer token-a")
                .header("Accept", "text/event-stream").content(SUBSCRIBE_TASK_X))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ROUTE_RESOLVE_FAILED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("resolve")));
    }
}
