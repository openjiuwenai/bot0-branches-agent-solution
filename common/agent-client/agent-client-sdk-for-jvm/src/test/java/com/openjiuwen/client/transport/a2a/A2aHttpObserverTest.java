/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.transport.spi.RawResponseEvent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Observer-related A2A transport tests.
 *
 * @since 2026-08-27
 */
class A2aHttpObserverTest {
    @Test
    void rawResponseObserverSeesCreateAndGetTask() throws Exception {
        List<RawResponseEvent> observed = new CopyOnWriteArrayList<>();
        CountDownLatch seen = new CountDownLatch(2);
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            String state = "GetTask".equals(method) ? "TASK_STATE_COMPLETED" : "TASK_STATE_WORKING";
            exchange.getResponseHeaders().set("X-Observation-Test", "yes");
            A2aHttpTestSupport.json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"obs\",\"result\":{\"task\":{"
                    + "\"id\":\"task-observed\",\"contextId\":\"observed\","
                    + "\"status\":{\"state\":\"" + state + "\"}}}}");
        })) {
            var executor = A2aHttpTestSupport.observerExecutor("a2a-observer-test");
            try {
                try (AgentClient client = AgentClients.builder()
                        .endpointType(com.openjiuwen.client.api.EndpointType.RUNTIME)
                        .endpointUrl(server.baseUrl())
                        .rawResponseObserver(event -> {
                            observed.add(event);
                            seen.countDown();
                        })
                        .rawResponseExecutor(executor)
                        .rawResponseFlushTimeout(Duration.ofSeconds(2))
                        .build()) {
                    InvocationCall call = client.invoke(InvocationRequest.runtimeBuilder()
                            .conversationId("observed")
                            .mode(InvocationMode.ASYNC)
                            .input("observe")
                            .build());
                    call.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);
                    client.getInvocation(call.invocationRef()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertTrue(seen.await(3, TimeUnit.SECONDS));
                }
            } finally {
                executor.shutdownNow();
            }
        }
        assertEquals(2, observed.size());
        assertEquals(RawResponseEvent.Source.CREATE_UNARY, observed.get(0).source());
        assertEquals(RawResponseEvent.Source.GET_TASK, observed.get(1).source());
        assertEquals("yes", observed.get(0).headers().get("x-observation-test").get(0));
    }
}
