/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

/**
 * Immutable cached routing decision: the next-hop {@code agentName} (an A2A
 * Gateway {@code agentCard}) and the L1 {@code responseContent} that produced
 * it. {@code expiresAt} is a monotonic-millis deadline checked by
 * {@link #isExpired(long)}.
 *
 * @param agentName the cached next-hop agent id (a2a_delegate payload
 *                  {@code agentName}).
 * @param responseContent the L1 response_content captured on the first turn;
 *                        kept for diagnostics, not reused on cache hit.
 * @param expiresAt absolute time in millis at which the entry expires.
 * @since 2026-07-25
 */
public record CachedRoute(String agentName, String responseContent, long expiresAt) {
    /**
     * Returns whether this entry has expired as of the supplied monotonic time.
     *
     * @param nowMillis the current monotonic time in millis
     * @return {@code true} if {@code nowMillis} is past {@code expiresAt}
     */
    public boolean isExpired(long nowMillis) {
        return nowMillis > expiresAt;
    }
}
