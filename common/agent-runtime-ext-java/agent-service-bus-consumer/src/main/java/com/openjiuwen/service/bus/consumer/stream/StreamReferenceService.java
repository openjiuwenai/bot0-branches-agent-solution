/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.stream;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates opaque, tenant/task-bound references for point-to-point A2A SSE subscriptions.
 *
 * @since 2026-07-22
 */
public final class StreamReferenceService {
    private final long ttlSeconds;
    private final ConcurrentHashMap<String, Reference> references = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     *
     * @param ttlSeconds
     *            the ttlSeconds value
     */
    public StreamReferenceService(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("stream reference ttl must be positive");
        }
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Performs the issue operation.
     *
     * @param tenantId
     *            the tenantId value
     * @param taskId
     *            the taskId value
     * @param nowEpochSeconds
     *            the nowEpochSeconds value
     *
     * @return the operation result
     */
    public String issue(String tenantId, String taskId, long nowEpochSeconds) {
        removeExpired(nowEpochSeconds);
        long expires = nowEpochSeconds + ttlSeconds;
        String opaqueId = UUID.randomUUID().toString();
        references.put(opaqueId, new Reference(tenantId, taskId, expires));
        return opaqueId;
    }

    /**
     * Performs the validate operation.
     *
     * @param reference
     *            the reference value
     * @param tenantId
     *            the tenantId value
     * @param taskId
     *            the taskId value
     * @param nowEpochSeconds
     *            the nowEpochSeconds value
     *
     * @return the operation result
     */
    public Reference validate(String reference, String tenantId, String taskId, long nowEpochSeconds) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("STREAM_REF_INVALID");
        }
        Reference stored = references.get(reference);
        if (stored == null) {
            throw new IllegalArgumentException("STREAM_REF_INVALID");
        }
        if (!tenantId.equals(stored.tenantId()) || !taskId.equals(stored.taskId())) {
            throw new IllegalArgumentException("STREAM_REF_SCOPE_INVALID");
        }
        if (stored.expiresAtEpochSeconds() < nowEpochSeconds) {
            references.remove(reference, stored);
            throw new IllegalArgumentException("STREAM_REF_EXPIRED");
        }
        return stored;
    }

    private void removeExpired(long nowEpochSeconds) {
        references.entrySet()
                .removeIf(entry -> entry.getValue().expiresAtEpochSeconds() < nowEpochSeconds);
    }

    public record Reference(String tenantId, String taskId, long expiresAtEpochSeconds) {
    }
}
