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

/**
 * Module-integration test for the A2A facade through G1 (auth) + G2 (tenant)
 * (FEAT-011 L2 §3.3 / §3.4). Real controller, real AuthRule, real TenantResolver,
 * real GovernanceErrorHandler; only the credential directory is faked (a bound
 * token and an unbound token). Asserts the HTTP-layer error shape and that a
 * bound credential passes governance.
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, TenantResolver.class, GovernanceErrorHandler.class})
class A2aControllerWebMvcTest {
    @Autowired
    private MockMvc mvc;

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
        // X-Tenant-Id differs from authoritative -> G2 discards it, request passes.
        mvc.perform(post("/a2a")
                        .header("Authorization", "Bearer bound-token")
                        .header("X-Tenant-Id", "tenant-other")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void boundCredentialPassesGovernance() throws Exception {
        mvc.perform(post("/a2a").header("Authorization", "Bearer bound-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotImplemented());
    }
}
