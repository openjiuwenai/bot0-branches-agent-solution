/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

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
import com.openjiuwen.gateway.governance.tenant.TenantResolver;
import com.openjiuwen.gateway.governance.validate.ParamValidator;

/**
 * Module-integration test for the A2A facade through G1 (auth) + G2 (tenant) +
 * G3 (validation) (FEAT-011 L2 §3.3 / §3.4 / §3.5). Real governance components
 * and advice; only the credential directory is faked.
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, TenantResolver.class, ParamValidator.class, GovernanceErrorHandler.class})
class A2aControllerWebMvcTest {
    @Autowired
    private MockMvc mvc;

    private static final String VALID_CREATE =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]}}}";
    private static final String EMPTY_AGENT_ID =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"\"}}}";
    private static final String BAD_METHOD =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\",\"params\":{}}";

    @TestConfiguration
    static class TestCredentials {
        @Bean
        CredentialDirectory credentialDirectory() {
            return token -> switch (token) {
                case "bound-token" -> Optional.of(new Principal("principal-1", "tenant-1"));
                case "unbound-token" -> Optional.of(new Principal("principal-2", null));
                default -> Optional.empty();
            };
        }
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
}
