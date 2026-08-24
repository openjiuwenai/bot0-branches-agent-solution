/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openjiuwen.gateway.bus.BusForwarder;
import com.openjiuwen.gateway.direct.FakeAgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
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
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.routing.StickyIndex;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

/**
 * Bus-mode dispatch: with {@code gateway.path-mode=bus} the facade forwards a sync create to
 * {@link BusForwarder#forwardSync} instead of the DIRECT {@link Router} (FEAT-012 §0.2 path-mode
 * is deployment-level and client-invisible). A stub BusForwarder records the dispatch.
 *
 * @since 2026-07-27
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, TenantResolver.class, ParamValidator.class, IdempotencyRule.class,
        GovernanceAuditor.class, GovernanceErrorHandler.class, Router.class, StickyIndex.class,
        SseBridge.class, PathSelector.class})
@TestPropertySource(properties = "gateway.path-mode=bus")
class A2aControllerBusDispatchTest {
    private static final String VALID_CREATE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"mb\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"agent-9\"}}}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private StubBusForwarder busForwarder;
    @Autowired
    private FakeRdcRouteClient rdc;
    @Autowired
    private FakeAgentRuntimeClient runtime;

    @TestConfiguration
    static class TestConfig {
        @Bean
        CredentialDirectory credentialDirectory() {
            return token -> "bound-token".equals(token)
                    ? Optional.of(new Principal("principal-1", "tenant-1"))
                    : Optional.empty();
        }

        @Bean
        AuditSink auditSink() {
            return new AuditSink() {
                @Override
                public void record(AuditEvent event) {
                    // no-op
                }
            };
        }

        @Bean
        FakeRdcRouteClient rdcRouteClient() {
            return new FakeRdcRouteClient();
        }

        @Bean
        FakeAgentRuntimeClient agentRuntimeClient() {
            return new FakeAgentRuntimeClient();
        }

        @Bean
        BusForwarder busForwarder() {
            return new StubBusForwarder();
        }
    }

    /** Stub BusForwarder: records dispatch + returns a fixed COMPLETED_RESPONSE body. */
    static class StubBusForwarder extends BusForwarder {
        boolean called;

        StubBusForwarder() {
            super(null, null, null, null, "stub-gw", 0L, 0L, null, new StickyIndex());
        }

        @Override
        public ResponseEntity<String> forwardSync(GovernanceContext ctx) {
            called = true;
            return ResponseEntity.ok().body("{\"result\":{\"status\":\"COMPLETED_RESPONSE\"}}");
        }
    }

    @BeforeEach
    void resetState() {
        busForwarder.called = false;
        rdc.setCandidates(List.of(new com.openjiuwen.gateway.routing.AgentCardRoute("h1")));
        runtime.setResponse("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"id\":\"task-9\"}}");
        runtime.reset();
    }

    @Test
    void busModeCreateDispatchesToBusForwarder() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"result\":{\"status\":\"COMPLETED_RESPONSE\"}}"));
        assertThat(busForwarder.called).isTrue();
        // DIRECT path not taken: the runtime HTTP client was never invoked
        assertThat(runtime.lastEndpoint()).isNull();
    }
}
