package com.openjiuwen.rdc.model.deployment;

/**
 * Raised when a provider replays the same {@code sourceRevision} with conflicting
 * snapshot content (Feat-015 0711 §5.1.4).
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
