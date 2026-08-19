/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.service.AgentDiscoveryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FEAT-016 HTTP contract for {@code POST /api/registry/route-handle/resolve}
 * (Feat-Func-016 L2 §7). Internal exception codes stay on Feat-015 names.
 */
class InstanceRouteApiExceptionHandlerTest {
    private AgentDiscoveryService discovery;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        discovery = mock(AgentDiscoveryService.class);
        AgentRegistryRepository repository = mock(AgentRegistryRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InstanceRouteController(discovery, repository))
                .setControllerAdvice(new InstanceRouteApiExceptionHandler())
                .build();
    }

    @Test
    void malformedHandleReturns400MalformedHandle() throws Exception {
        when(discovery.resolveRouteHandle(anyString(), anyString()))
                .thenThrow(new MalformedRouteHandleException("bad handle", "trace-malformed"));

        String body = mockMvc.perform(post("/api/registry/route-handle/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeHandle\":\"!!!not-base64!!!\",\"tenantId\":\"tenant-A\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"error\":\"malformed_handle\"");
        assertThat(body).doesNotContain("MALFORMED_ROUTE_HANDLE");
    }

    @Test
    void crossTenantResolveReturns400TenantIsolationViolation() throws Exception {
        when(discovery.resolveRouteHandle(anyString(), anyString()))
                .thenThrow(new TenantIsolationViolationException("tenant-A", "tenant-B", "trace-tenant"));

        String body = mockMvc.perform(post("/api/registry/route-handle/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeHandle\":\"v2:stub\",\"tenantId\":\"tenant-B\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"error\":\"tenant_isolation_violation\"");
        assertThat(body).doesNotContain("TENANT_SCOPE_DENIED");
    }

    @Test
    void missingEntryReturns404EntryNotFound() throws Exception {
        when(discovery.resolveRouteHandle(anyString(), anyString()))
                .thenThrow(new EntryNotFoundException("no row", "trace-missing"));

        String body = mockMvc.perform(post("/api/registry/route-handle/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeHandle\":\"v2:stub\",\"tenantId\":\"tenant-A\"}"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("\"error\":\"entry_not_found\"");
        assertThat(body).doesNotContain("ENTRY_NOT_FOUND");
    }
}
