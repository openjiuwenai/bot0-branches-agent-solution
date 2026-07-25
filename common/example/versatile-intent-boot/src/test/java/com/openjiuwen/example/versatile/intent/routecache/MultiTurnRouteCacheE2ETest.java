/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import com.openjiuwen.example.versatile.intent.mock.MockVersatileController;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end integration test for the multi-turn route cache.
 *
 * <p>Boots the full Spring context under the {@code layer1} + {@code mock-versatile}
 * profiles on a fixed servlet port. The {@link MockVersatileController} runs in
 * the same servlet container, and {@code @TestPropertySource} overrides the
 * versatile URL template to point at the in-process mock endpoint. The test
 * drives {@link AgentHandler#query} directly on the cached handler bean.
 *
 * <p>A fixed port (rather than {@code RANDOM_PORT} + {@code @DynamicPropertySource})
 * is required because the URL template is consumed by
 * {@code VersatileAgentHandler} during context initialization (it is set on the
 * {@link com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties}
 * bean), which happens before {@code @LocalServerPort} is injected into the
 * test instance. A fixed port lets the template be wired statically.
 *
 * <p>Round 1: cache miss → {@code VersatileAgentHandler} POSTs to
 * {@link MockVersatileController} → mock returns the L1 three-field envelope
 * → extractor builds an {@code a2a_delegate} interrupt →
 * {@link CachedVersatileAgentHandler} captures {@code agentName} into
 * {@link RouteCache}. Mock counter = 1.
 *
 * <p>Round 2 (same conversationId): cache hit → synthetic
 * {@code a2a_delegate} result, no HTTP call. Mock counter stays at 1.
 *
 * @since 2026-07-25
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles({"layer1", "mock-versatile"})
@TestPropertySource(properties = {
        // Fixed port so the URL template can be statically wired to the
        // in-process MockVersatileController. {project_id} is NOT substituted
        // by VersatileRequestExtractor (only {conversation_id} is), so we
        // hardcode the literal "proj" segment that MockVersatileController binds.
        "server.port=18081",
        "openjiuwen.service.versatile.url-template="
                + "http://localhost:18081/v1/proj/agents/agent_L1/conversations/{conversation_id}"
})
class MultiTurnRouteCacheE2ETest {

    @Autowired
    private AgentHandler handler;

    @Autowired
    private MockVersatileController mockController;

    @Test
    void secondTurnReusesCachedRouteSkippingL1() {
        mockController.resetCounters();
        assertEquals(0, mockController.getL1InvocationCount());

        // Round 1: L1 mock invoked, a2a_delegate captured into the cache.
        handler.query(req("c-e2e-1", "我要订酒店"));
        assertEquals(1, mockController.getL1InvocationCount(),
                "L1 must be invoked on first turn");

        // Round 2: cache hit, L1 mock NOT invoked again.
        handler.query(req("c-e2e-1", "上海今晚五星"));
        assertEquals(1, mockController.getL1InvocationCount(),
                "L1 must NOT be invoked on second turn (cache hit)");
    }

    @Test
    void differentConversationsEachInvokeL1() {
        mockController.resetCounters();
        handler.query(req("c-e2e-2", "订酒店"));
        handler.query(req("c-e2e-3", "订机票"));
        assertEquals(2, mockController.getL1InvocationCount());
    }

    @Test
    void resetConversationClearsCacheAndNextTurnReInvokesL1() {
        mockController.resetCounters();
        handler.query(req("c-e2e-4", "订酒店"));
        assertEquals(1, mockController.getL1InvocationCount());

        handler.clearSession("c-e2e-4");
        handler.query(req("c-e2e-4", "订酒店"));
        assertEquals(2, mockController.getL1InvocationCount(),
                "L1 must be re-invoked after clearSession");
    }

    private static ServeRequest req(String convId, String userQuery) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setUserId("u1");
        r.setTenantId("t1");
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userQuery);
        r.setMessages(new ArrayList<>(List.of(userMsg)));
        return r;
    }
}
