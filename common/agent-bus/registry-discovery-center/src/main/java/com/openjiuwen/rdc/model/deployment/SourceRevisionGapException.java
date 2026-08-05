/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

/**
 * Raised when a provider event stream has a non-recoverable revision gap
 * (Feat-015 0711 {@code SOURCE_REVISION_GAP}).
 *
 * @since 0.1.0 (2026)
 */
public final class SourceRevisionGapException extends RuntimeException {
    private final String sourceId;

    public SourceRevisionGapException(String sourceId, String message) {
        super(message);
        this.sourceId = sourceId;
    }

    /**
     * sourceId.
     *
     * @return result
     * @since 0.1.0
     */
    public String sourceId() {
        return sourceId;
    }
}
