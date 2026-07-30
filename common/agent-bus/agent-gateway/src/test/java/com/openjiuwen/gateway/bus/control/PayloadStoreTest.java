/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayloadStore} / {@link InMemoryPayloadStore}.
 *
 * @since 2026-07-24
 */
class PayloadStoreTest {
    private final PayloadStore store = new InMemoryPayloadStore();

    @Test
    void stashAndFetchRoundtrip() {
        String ref = store.stash("{\"jsonrpc\":\"2.0\"}");
        assertThat(ref).isNotBlank();
        assertThat(store.fetch(ref)).contains("{\"jsonrpc\":\"2.0\"}");
    }

    @Test
    void fetchMissingReturnsEmpty() {
        assertThat(store.fetch("ghost")).isEmpty();
    }

    @Test
    void stashDifferentBodiesGetDifferentRefs() {
        String refA = store.stash("bodyA");
        String refB = store.stash("bodyB");
        assertThat(refA).isNotEqualTo(refB);
    }
}
