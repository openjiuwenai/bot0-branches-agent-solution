/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.example.versatile.intent.routecache.CachedRoute;
import com.openjiuwen.example.versatile.intent.routecache.InProcessRouteCache;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies that {@link ReclassifyServeOrchestrator} clears the L1
 * {@link com.openjiuwen.example.versatile.intent.routecache.RouteCache}
 * for the active conversation before retrying L1 with an augmented
 * request. Without invalidation the retry would skip L1 via the cache
 * and reuse the rejected route.
 *
 * @since 2026-07-25
 */
class ReclassifyServeOrchestratorCacheInvalidationTest {
    private InProcessRouteCache cache;
    private ReclassifyProperties props;
    private ServeOrchestrator wrapped;
    private ReclassifyServeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        cache = new InProcessRouteCache(Duration.ofMinutes(30), () -> 1_000L);
        props = new ReclassifyProperties();
        props.setEnabled(true);
        props.setMaxReclassify(1);
        props.setAmbiguousIntentId("1");
        wrapped = mock(ServeOrchestrator.class);
        orchestrator = new ReclassifyServeOrchestrator(wrapped, props, Optional.of(cache));
    }

    @Test
    void reclassifyTriggersCacheInvalidation() {
        // Given: cache already has an entry for c1 (from a previous L1 turn)
        cache.put("c1", new CachedRoute("agent_card_layer2_hotel", "old rc", 10_000_000L));
        assertThat(cache.get("c1")).isPresent();

        // And: wrapped returns an ambiguous envelope on first call, plain answer on second
        QueryResponse first = new QueryResponse();
        first.setResult(Map.of(
                "role", "assistant",
                "content", "{\"type\":\"answer\",\"intent_id\":\"1\","
                        + "\"payload\":{\"content\":\"无法确定\"}}"));
        QueryResponse second = new QueryResponse();
        second.setResult(Map.of("role", "assistant", "content", "重新分类成功"));
        when(wrapped.query(any(ServeRequest.class))).thenReturn(first, second);

        ServeRequest req = mock(ServeRequest.class);
        when(req.getConversationId()).thenReturn("c1");
        when(req.getMessages()).thenReturn(List.of());
        when(req.getMetadata()).thenReturn(new LinkedHashMap<>());
        when(req.isStream()).thenReturn(false);

        orchestrator.query(req);

        // Then: cache must be invalidated before the retry so L1 re-runs
        assertThat(cache.get("c1"))
                .as("cache must be invalidated when reclassify is triggered")
                .isEmpty();
        verify(wrapped, times(2)).query(any(ServeRequest.class));
    }

    @Test
    void noReclassifyLeavesCacheUntouched() {
        cache.put("c1", new CachedRoute("agent_card_layer2_hotel", "rc", 10_000_000L));
        QueryResponse answer = new QueryResponse();
        answer.setResult(Map.of("role", "assistant", "content", "正常答案"));
        when(wrapped.query(any(ServeRequest.class))).thenReturn(answer);

        ServeRequest req = mock(ServeRequest.class);
        when(req.getConversationId()).thenReturn("c1");
        when(req.getMessages()).thenReturn(List.of());
        when(req.getMetadata()).thenReturn(new LinkedHashMap<>());
        when(req.isStream()).thenReturn(false);

        orchestrator.query(req);

        assertThat(cache.get("c1"))
                .as("cache must NOT be invalidated when no reclassify")
                .isPresent();
    }
}
