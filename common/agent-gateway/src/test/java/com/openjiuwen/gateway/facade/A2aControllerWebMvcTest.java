/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

/**
 * Module-integration test for the A2A facade through G1–G5
 * (FEAT-011 L2 §3.3–§3.7). Real governance components; credential directory and
 * audit sink are faked. Idempotency + audit state are cleared before each test.
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, TenantResolver.class, ParamValidator.class, IdempotencyRule.class,
        GovernanceAuditor.class, GovernanceErrorHandler.class})
class A2aControllerWebMvcTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private IdempotencyRule idempotencyRule;
    @Autowired
    private CapturingAuditSink auditSink;

    private static final String VALID_CREATE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]}}}";
    private static final String EMPTY_AGENT_ID =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"\"}}}";
    private static final String BAD_METHOD =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\",\"params\":{}}";
    private static final String BODY_A =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m-dup\",\"parts\":[{\"text\":\"hi\"}]}}}";
    private static final String BODY_B =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m-dup\",\"parts\":[{\"text\":\"bye\"}]}}}";
    private static final String NO_MESSAGE_ID =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"parts\":[{\"text\":\"hi\"}]}}}";
    private static final String RESUME_BODY =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m-res\",\"taskId\":\"task-1\",\"parts\":[]}}}";

    @TestConfiguration
    static class TestConfig {
        @Bean
        CredentialDirectory credentialDirectory() {
            return token -> switch (token) {
                case "bound-token" -> Optional.of(new Principal("principal-1", "tenant-1"));
                case "unbound-token" -> Optional.of(new Principal("principal-2", null));
                default -> Optional.empty();
            };
        }

        @Bean
        CapturingAuditSink auditSink() {
            return new CapturingAuditSink();
        }
    }

    /** Capturing AuditSink so tests can assert recorded governance events. */
    static class CapturingAuditSink implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    @BeforeEach
    void resetState() {
        idempotencyRule.clear();
        auditSink.events.clear();
    }

    // --- G1 ---

    @Test
    void noAuthorizationReturns401AuthMissing() throws Exception {
        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING"));
    }

    @Test
    void nonBearerSchemeReturns401AuthInvalid() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Basic abc")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void unknownTokenReturns401AuthInvalid() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    // --- G2 ---

    @Test
    void unboundCredentialReturns403TenantUnresolved() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer unbound-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_UNRESOLVED"));
    }

    @Test
    void conflictingSelfReportStillPasses() throws Exception {
        mvc.perform(post("/a2a")
                        .header("Authorization", "Bearer bound-token")
                        .header("X-Tenant-Id", "tenant-other")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void boundCredentialPassesGovernance() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isNotImplemented());
    }

    // --- G3 ---

    @Test
    void emptyAgentIdReturns400ValidationAgentId() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(EMPTY_AGENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_AGENT_ID"));
    }

    @Test
    void badMethodReturns400ValidationMethod() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_METHOD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_METHOD"));
    }

    @Test
    void malformedBodyReturns400ValidationJsonrpc() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_JSONRPC"));
    }

    // --- G4 ---

    @Test
    void sameMessageIdDifferentBodyReturns409Conflict() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_A))
                .andExpect(status().isNotImplemented());
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_B))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));
    }

    @Test
    void createWithoutMessageIdProceedsTwice() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(NO_MESSAGE_ID))
                .andExpect(status().isNotImplemented());
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(NO_MESSAGE_ID))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void resumeWithTaskIdSkipsIdempotency() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isNotImplemented());
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(RESUME_BODY))
                .andExpect(status().isNotImplemented());
    }

    // --- G5 ---

    @Test
    void passedRequestIsAuditedPassedWithSelfGeneratedTraceId() throws Exception {
        // No traceparent -> self-generated traceId; main path does not fail.
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CREATE))
                .andExpect(status().isNotImplemented());
        AuditEvent passed = auditSink.events.stream()
                .filter(e -> e.outcome() == AuditEvent.Outcome.PASSED).findFirst().orElseThrow();
        assertThat(passed.tenantId()).isEqualTo("tenant-1");
        assertThat(passed.method()).isEqualTo("SendMessage");
        assertThat(passed.traceId()).isNotBlank();
    }

    @Test
    void g1FailureAuditedAsRejectedWithStage() throws Exception {
        mvc.perform(post("/a2a").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        AuditEvent rejected = auditSink.events.stream()
                .filter(e -> e.outcome() == AuditEvent.Outcome.REJECTED).findFirst().orElseThrow();
        assertThat(rejected.rejectStage()).isEqualTo("G1");
        assertThat(rejected.code()).isEqualTo("AUTH_MISSING");
    }
}
