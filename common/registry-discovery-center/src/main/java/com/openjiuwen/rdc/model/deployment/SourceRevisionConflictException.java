/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

/**
 * Raised when a provider replays the same {@code sourceRevision} with conflicting
 * snapshot content (Feat-015 0711 §5.1.4).
 *
 * @since 0.1.0
 */
public final class SourceRevisionConflictException extends RuntimeException {

    private final String sourceId;
    private final long sourceRevision;

    public SourceRevisionConflictException(String sourceId, long sourceRevision, String message) {
        super(message);
        this.sourceId = sourceId;
        this.sourceRevision = sourceRevision;
    }

    public String sourceId() {
        return sourceId;
    }

    public long sourceRevision() {
        return sourceRevision;
    }
}
