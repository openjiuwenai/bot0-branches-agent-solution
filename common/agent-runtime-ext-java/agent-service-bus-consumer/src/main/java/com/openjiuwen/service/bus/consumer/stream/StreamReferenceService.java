/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.stream;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Creates opaque, tenant/task-bound references for point-to-point A2A SSE subscriptions.
 *
 * @since 2026-07-22
 */
public final class StreamReferenceService {
    private final byte[] secret;
    private final long ttlSeconds;
    private final String keyId = "k1";
    private final ConcurrentHashMap<String, Reference> references = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     *
     * @param secret
     *            the secret value
     * @param ttlSeconds
     *            the ttlSeconds value
     */
    public StreamReferenceService(String secret, long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("stream reference secret is required");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("stream reference ttl must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
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
        long expires = nowEpochSeconds + ttlSeconds;
        String opaqueId = UUID.randomUUID().toString();
        String body = keyId + ":" + opaqueId + ":" + expires;
        references.put(opaqueId, new Reference(tenantId, taskId, expires));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "."
                + sign(body);
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
        String[] parts = reference.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("STREAM_REF_INVALID");
        }
        String body = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("STREAM_REF_INVALID");
        }
        String[] fields = body.split(":", 3);
        if (fields.length != 3) {
            throw new IllegalArgumentException("STREAM_REF_INVALID");
        }
        if (!keyId.equals(fields[0])) {
            throw new IllegalArgumentException("STREAM_REF_KEY_UNKNOWN");
        }
        long expires = parseExpiry(fields[2]);
        Reference stored = references.get(fields[1]);
        if (stored == null || !tenantId.equals(stored.tenantId()) || !taskId.equals(stored.taskId())) {
            throw new IllegalArgumentException("STREAM_REF_SCOPE_INVALID");
        }
        if (expires < nowEpochSeconds) {
            throw new IllegalArgumentException("STREAM_REF_EXPIRED");
        }
        return stored;
    }

    private static long parseExpiry(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("STREAM_REF_INVALID", failure);
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to sign stream reference", failure);
        }
    }

    public record Reference(String tenantId, String taskId, long expiresAtEpochSeconds) {
    }
}
