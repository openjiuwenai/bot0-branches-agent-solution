package com.openjiuwen.rdc.model;

/** Registry entry lifecycle (Feat-015 0711 scope §5.1.5). */
public enum LifecycleStatus {
    PENDING,
    ACTIVE,
    DRAINING,
    REMOVED
}
