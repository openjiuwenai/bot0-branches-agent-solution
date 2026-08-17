/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway process-local RDC route cache smoke (no MockWebServer): tenant-scoped put/get + TTL.
 */
class LocalRdcRouteCacheSmokeTest {
    @Test
    void searchAndResolveSurviveWithinTtlAndExpireAfter() {
        AtomicLong clock = new AtomicLong(1_000L);
        LocalRdcRouteCache cache = new LocalRdcRouteCache(Duration.ofMillis(5_000), clock::get);

        cache.putSearch("t1", "a1", List.of(new AgentCardRoute("h1", "svc-1")));
        cache.putResolve("t1", "h1", "http://rt/a2a");

        assertThat(cache.getSearch("t1", "a1"))
                .hasValueSatisfying(list -> assertThat(list.get(0).routeHandle()).isEqualTo("h1"));
        assertThat(cache.getResolve("t1", "h1")).contains("http://rt/a2a");
        assertThat(cache.getSearch("other", "a1")).isEmpty();

        clock.set(1_000L + 5_001L);
        assertThat(cache.getSearch("t1", "a1")).isEmpty();
        assertThat(cache.getResolve("t1", "h1")).isEmpty();
    }

    @Test
    void emptySearchIsNotCached() {
        LocalRdcRouteCache cache = new LocalRdcRouteCache(Duration.ofSeconds(30), System::currentTimeMillis);
        cache.putSearch("t1", "a1", List.of());
        assertThat(cache.getSearch("t1", "a1")).isEmpty();
    }
}
