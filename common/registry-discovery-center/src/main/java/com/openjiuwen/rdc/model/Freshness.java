package com.openjiuwen.rdc.model;

/** Card snapshot freshness (Feat-015 0713 scope §5.1.4). */
public enum Freshness {
    FRESH,
    STALE_SOURCE,
    STALE_CARD
}
