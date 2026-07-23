/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full-context smoke (distinct from the sliced {@link A2aControllerWebMvcTest}):
 * boots the real {@code @SpringBootApplication} so every {@code @Component}
 * (AuthRule, PropertiesCredentialDirectory, controller, advice) wires together,
 * then exercises the real G1 path end-to-end. With no test credential configured,
 * an unauthenticated request must be rejected at the HTTP layer with
 * {@code AUTH_MISSING}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class A2aRouteSmokeTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void contextBootsAndUnauthenticatedIsRejected() throws Exception {
        mvc.perform(post("/a2a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_MISSING"));
    }
}
