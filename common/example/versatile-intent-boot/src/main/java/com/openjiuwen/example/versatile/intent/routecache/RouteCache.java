package com.openjiuwen.example.versatile.intent.routecache;

import java.util.Optional;

/**
 * Conversation-level route cache used by {@link CachedVersatileAgentHandler}
 * to skip L1 intent recognition on subsequent turns within the same
 * conversation.
 *
 * <p>Implementations must be thread-safe. Loss of cached entries is
 * acceptable (worst case: L1 re-runs).
 *
 * @since 2026-07-25
 */
public interface RouteCache {
    /** Looks up a cached route. Returns empty if absent or expired. */
    Optional<CachedRoute> get(String conversationId);

    /** Stores a route for the given conversation id. */
    void put(String conversationId, CachedRoute route);

    /** Removes any cached entry for the given conversation id. */
    void invalidate(String conversationId);
}