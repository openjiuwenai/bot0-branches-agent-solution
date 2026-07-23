package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
