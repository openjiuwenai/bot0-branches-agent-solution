/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies cache hit/miss behavior of {@link CachedVersatileAgentHandler} for
 * both the request/response and streaming paths.
 *
 * @since 2026-07-25
 */
class CachedVersatileAgentHandlerTest {
    private final AtomicLong now = new AtomicLong(1_000L);
    private final RouteCacheProperties props = new RouteCacheProperties();
    private RecordingHandler delegate;
    private InProcessRouteCache cache;
    private CachedVersatileAgentHandler handler;

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setTtl(Duration.ofMillis(500L));
        delegate = new RecordingHandler();
        cache = new InProcessRouteCache(props.getTtl(), now::get);
        handler = new CachedVersatileAgentHandler(delegate, cache, props, now::get);
    }

    @Test
    void queryMissPopulatesCacheFromA2aDelegateResult() {
        QueryResponse first = handler.query(req("c1", "订酒店"));
        assertEquals(1, delegate.queryCalls.get());
        Optional<CachedRoute> cached = cache.get("c1");
        assertTrue(cached.isPresent());
        assertEquals("agent_card_layer2_hotel", cached.get().agentName());
        assertEquals("L1 output", cached.get().responseContent());
        assertTrue(first.getResult() instanceof Map);
    }

    @Test
    void queryHitSkipsDelegateAndProducesSyntheticA2aDelegate() {
        handler.query(req("c1", "订酒店"));
        delegate.queryCalls.set(0); // reset counter

        QueryResponse second = handler.query(req("c1", "上海今晚五星"));
        assertEquals(0, delegate.queryCalls.get(), "delegate must NOT be called on cache hit");
        Object result = second.getResult();
        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> interrupt = (Map<String, Object>) ((Map<String, Object>) result).get("_interrupt");
        assertNotNull(interrupt);
        assertEquals("agent_card_layer2_hotel", interrupt.get("agentName"));
        assertEquals("", interrupt.get("responseContent"));
        assertEquals(false, interrupt.get("resume"));
        assertEquals("上海今晚五星", interrupt.get("message"));
    }

    @Test
    void queryNonA2aDelegateResultIsNotCached() {
        delegate.plainAnswer = true;
        handler.query(req("c1", "随便问"));
        assertTrue(cache.get("c1").isEmpty());
    }

    @Test
    void queryNullConversationIdBypassesCache() {
        QueryResponse r = handler.query(req(null, "no-conv"));
        assertNotNull(r);
        assertEquals(1, delegate.queryCalls.get());
        // ensure no NPE; nothing to invalidate
        assertDoesNotThrow(() -> handler.clearSession(null));
    }

    @Test
    void clearSessionInvalidatesCache() {
        handler.query(req("c1", "订酒店"));
        assertTrue(cache.get("c1").isPresent());
        handler.clearSession("c1");
        assertTrue(cache.get("c1").isEmpty());
        assertEquals(1, delegate.clearSessionCalls.get());
    }

    @Test
    void streamQueryHitSkipsDelegateAndEmitsSingleInterruptChunk() {
        handler.query(req("c1", "订酒店")); // populate cache via query path
        delegate.queryCalls.set(0);

        AtomicReference<List<QueryChunk>> emitted = new AtomicReference<>(new ArrayList<>());
        QueryStreamObserver observer = new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                emitted.get().add(chunk);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
        handler.streamQuery(req("c1", "上海今晚五星"), observer);
        assertEquals(0, delegate.streamQueryCalls.get(), "delegate streamQuery must NOT be called on cache hit");
        assertEquals(1, emitted.get().size());
        QueryChunk chunk = emitted.get().get(0);
        assertEquals(QueryChunk.TYPE_INTERRUPT, chunk.getType());
        assertInstanceOf(Map.class, chunk.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) ((Map<String, Object>) chunk.getData()).get("context");
        assertEquals("a2a_delegate", ctx.get("_interrupt_kind"));
    }

    @Test
    void streamQueryMissPopulatesCacheFromObservedChunk() {
        delegate.streamChunks = List.of(
                new QueryChunk(QueryChunk.TYPE_INTERRUPT, A2aDelegatePayload.buildSyntheticPayload(
                        "agent_card_layer2_flight", "flight output", "订机票", true)));
        AtomicReference<List<QueryChunk>> emitted = new AtomicReference<>(new ArrayList<>());
        QueryStreamObserver observer = recObserver(emitted);
        handler.streamQuery(req("c2", "订机票"), observer);
        assertEquals(1, delegate.streamQueryCalls.get());
        Optional<CachedRoute> cached = cache.get("c2");
        assertTrue(cached.isPresent());
        assertEquals("agent_card_layer2_flight", cached.get().agentName());
        assertEquals("flight output", cached.get().responseContent());
        assertEquals(1, emitted.get().size(), "wrapped chunk must be forwarded to observer");
    }

    @Test
    void streamQueryNonA2aDelegateChunksDoNotPopulateCache() {
        delegate.streamChunks = List.of(
                new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of("type", "answer", "output", "plain")));
        handler.streamQuery(req("c3", "q"), recObserver(new AtomicReference<>(new ArrayList<>())));
        assertTrue(cache.get("c3").isEmpty());
    }

    private static QueryStreamObserver recObserver(AtomicReference<List<QueryChunk>> sink) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                sink.get().add(chunk);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private ServeRequest req(String convId, String userQuery) {
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

    static class RecordingHandler implements AgentHandler {
        final AtomicLong queryCalls = new AtomicLong();
        final AtomicLong streamQueryCalls = new AtomicLong();
        final AtomicLong clearSessionCalls = new AtomicLong();
        volatile boolean plainAnswer = false;
        volatile List<QueryChunk> streamChunks = List.of();

        @Override
        public QueryResponse query(ServeRequest request) {
            queryCalls.incrementAndGet();
            QueryResponse resp = new QueryResponse();
            resp.setConversationId(request.getConversationId());
            if (plainAnswer) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("role", "assistant");
                result.put("content", "plain answer");
                resp.setResult(result);
                return resp;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", "assistant");
            result.put("content", "L1 output");
            result.put("_interrupt", A2aDelegatePayload.buildSyntheticPayload(
                    "agent_card_layer2_hotel", "L1 output", request.lastUserQuery(), false));
            resp.setResult(result);
            return resp;
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            streamQueryCalls.incrementAndGet();
            for (QueryChunk chunk : streamChunks) {
                observer.onNext(chunk);
            }
            observer.onComplete();
        }

        @Override
        public void clearSession(String conversationId) {
            clearSessionCalls.incrementAndGet();
        }
    }
}
