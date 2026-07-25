package com.openjiuwen.example.versatile.intent.routecache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class InProcessRouteCacheTest {
    private final AtomicLong now = new AtomicLong(1_000L);
    private final InProcessRouteCache cache = new InProcessRouteCache(Duration.ofMillis(500L), now::get);

    @Test
    void putThenGetReturnsRoute() {
        cache.put("c1", new CachedRoute("agentX", "rc", now.get() + 500L));
        Optional<CachedRoute> got = cache.get("c1");
        assertTrue(got.isPresent());
        assertEquals("agentX", got.get().agentName());
    }

    @Test
    void getReturnsEmptyForUnknownConversation() {
        assertTrue(cache.get("missing").isEmpty());
    }

    @Test
    void getReturnsEmptyAfterTtlExpiry() {
        cache.put("c1", new CachedRoute("agentX", "rc", now.get() + 500L));
        now.set(1_600L); // past expiry
        assertTrue(cache.get("c1").isEmpty());
    }

    @Test
    void invalidateRemovesEntry() {
        cache.put("c1", new CachedRoute("agentX", "rc", now.get() + 500L));
        cache.invalidate("c1");
        assertTrue(cache.get("c1").isEmpty());
    }

    @Test
    void invalidateOnUnknownConversationIsNoop() {
        assertDoesNotThrow(() -> cache.invalidate("never-existed"));
    }

    @Test
    void putOverwritesPreviousEntry() {
        cache.put("c1", new CachedRoute("agentA", "rc1", now.get() + 500L));
        cache.put("c1", new CachedRoute("agentB", "rc2", now.get() + 500L));
        assertEquals("agentB", cache.get("c1").orElseThrow().agentName());
    }
}