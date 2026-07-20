package com.openjiuwen.rdc.model;

import java.util.Objects;

/** Optional load-balancing hint for callers (Feat-015 0711 {@code selectionHint}). */
public record SelectionHint(
        int weight,
        @Nullable String region
) {
    public SelectionHint {
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be >= 0");
        }
    }
}
