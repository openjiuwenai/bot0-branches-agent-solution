/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.RouteResolution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDC process-local route cache smoke (no DB): tenant-scoped put/get + TTL expiry.
 */
class RdcLocalRouteCacheSmokeTest {
    @Test
    void searchAndResolveSurviveWithinTtlAndExpireAfter() {
        AtomicLong clock = new AtomicLong(1_000L);
        RdcLocalRouteCache cache = new RdcLocalRouteCache(Duration.ofMillis(5_000), clock::get);

        AgentCardDto card = AgentCardDto.builder()
                .serviceId("s1")
                .routeHandle("h1")
                .health("ONLINE")
                .contractVersion("1.0.0")
                .capabilityVersion("1.0.0")
                .weight(100)
                .region("default")
                .maxConcurrency(10)
                .build();
        cache.putSearch("agentId", "t1", "a1", null, List.of(card));
        cache.putResolve("t1", "h1",
                new RouteResolution("s1", "http://rt/a2a", "/v1", "1.0.0", null));

        assertThat(cache.getSearch("agentId", "t1", "a1", null))
                .hasValueSatisfying(list -> assertThat(list.get(0).getRouteHandle()).isEqualTo("h1"));
        assertThat(cache.getResolve("t1", "h1"))
                .hasValueSatisfying(r -> assertThat(r.endpointUrl()).isEqualTo("http://rt/a2a"));
        assertThat(cache.getSearch("agentId", "other", "a1", null)).isEmpty();

        clock.set(1_000L + 5_001L);
        assertThat(cache.getSearch("agentId", "t1", "a1", null)).isEmpty();
        assertThat(cache.getResolve("t1", "h1")).isEmpty();
    }

    @Test
    void emptySearchIsNotCached() {
        RdcLocalRouteCache cache = new RdcLocalRouteCache(Duration.ofSeconds(30), System::currentTimeMillis);
        cache.putSearch("agentId", "t1", "a1", null, List.of());
        assertThat(cache.getSearch("agentId", "t1", "a1", null)).isEmpty();
    }
}
