package com.openjiuwen.rdc.model.deployment;

/**
 * Raised when a provider event stream has a non-recoverable revision gap
 * (Feat-015 0711 {@code SOURCE_REVISION_GAP}).
 */
public final class SourceRevisionGapException extends RuntimeException {

    private final String sourceId;

    public SourceRevisionGapException(String sourceId, String message) {
        super(message);
        this.sourceId = sourceId;
    }

    public String sourceId() {
        return sourceId;
    }
}
