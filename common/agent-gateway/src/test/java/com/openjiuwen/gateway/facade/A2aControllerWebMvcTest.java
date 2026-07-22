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

/**
 * Module-integration test for the A2A facade at the G1 (authentication) slice
 * (FEAT-011 L2 §3.3 — T-G1-1..T-G1-4). Loads the real controller, real G1
 * {@link AuthRule}, and the real {@link GovernanceErrorHandler}; only the
 * credential directory is faked (a test token). Asserts the HTTP-layer error
 * shape (status + stable {@code code}) and that a valid token passes G1.
 */
@WebMvcTest(controllers = A2aController.class)
@Import({AuthRule.class, GovernanceErrorHandler.class})
class A2aControllerWebMvcTest {
    @Autowired
    private MockMvc mvc;

    @TestConfiguration
    static class TestCredentials {
        @Bean
        CredentialDirectory credentialDirectory() {
            return token -> "good-token".equals(token)
                    ? Optional.of(new Principal("principal-1", "tenant-1"))
                    : Optional.empty();
        }
    }

    @Test
    void noAuthorizationReturns401AuthMissing() throws Exception {
        mvc.perform(post("/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING"));
    }

    @Test
    void nonBearerSchemeReturns401AuthInvalid() throws Exception {
        mvc.perform(post("/a2a")
                        .header("Authorization", "Basic abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void unknownTokenReturns401AuthInvalid() throws Exception {
        mvc.perform(post("/a2a")
                        .header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void validTokenPassesG1() throws Exception {
        // Auth passes ⇒ not 401; returns the post-G1 placeholder (501) until
        // G2+/routing/forwarding are wired.
        mvc.perform(post("/a2a")
                        .header("Authorization", "Bearer good-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotImplemented());
    }
}
