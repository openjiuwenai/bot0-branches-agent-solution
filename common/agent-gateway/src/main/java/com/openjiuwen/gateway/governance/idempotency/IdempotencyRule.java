/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.idempotency;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * G4 — create idempotency (FEAT-011 L2 §3.6). For create-class requests (no
 * {@code taskId}), dedups on {@code tenantId + params.message.messageId} so a
 * client retry does not create a second Task. Resume (has {@code taskId}) and
 * requests with no {@code messageId} skip this entirely.
 *
 * <p>730 in-memory single-process store (decision D4). Multi-instance Gateway
 * would need shared storage — deliberately out of 730 scope (documented).
 *
 * <p>Fingerprint is the raw create body (730 simplification of L2 §3.6.1
 * "规范化正文摘要"; since the body carries {@code params.metadata.agentId}, an
 * agentId change also changes the fingerprint). Same key + different fingerprint
 * -> CONFLICT (409). Same key + same fingerprint + completed -> REPLAY the prior
 * result. Same key + same fingerprint + still in-flight -> IN_FLIGHT_DUPLICATE.
 *
 * @since 0.1.0
 */
@Component
public class IdempotencyRule {
    /** G4 outcome for the controller. */
    public enum Outcome {
        /** No messageId present -> skip dedup, proceed. */
        SKIP,
        /** First occurrence -> registered in-flight, proceed. */
        NEW,
        /** Completed prior create with same fingerprint -> short-circuit prior result. */
        REPLAY,
        /** Same key but different fingerprint -> 409 payload mismatch. */
        CONFLICT,
        /** Same key, same fingerprint, still in-flight -> do not open a second delivery. */
        IN_FLIGHT_DUPLICATE
    }

    /** G4 decision returned to the controller. */
    public record Decision(Outcome outcome, String result) {
    }

    private record Entry(String fingerprint, boolean completed, String result) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * Check / register a create against the idempotency window.
     *
     * @param tenantId   authoritative tenant (G2)
     * @param messageId  create idempotency key (may be blank -> SKIP)
     * @param fingerprint create body fingerprint (raw body)
     * @return decision telling the controller how to proceed
     */
    public Decision check(String tenantId, String messageId, String fingerprint) {
        if (messageId == null || messageId.isBlank()) {
            return new Decision(Outcome.SKIP, null);
        }
        String key = key(tenantId, messageId);
        Entry existing = store.get(key);
        if (existing == null) {
            store.put(key, new Entry(fingerprint, false, null));
            return new Decision(Outcome.NEW, null);
        }
        if (!existing.fingerprint.equals(fingerprint)) {
            return new Decision(Outcome.CONFLICT, null);
        }
        if (existing.completed) {
            return new Decision(Outcome.REPLAY, existing.result);
        }
        return new Decision(Outcome.IN_FLIGHT_DUPLICATE, null);
    }

    /**
     * Mark a create completed with its result (so later same-key retries replay).
     *
     * @param tenantId  authoritative tenant
     * @param messageId create idempotency key
     * @param result    the result body to replay on retry
     */
    public void complete(String tenantId, String messageId, String result) {
        Entry existing = store.get(key(tenantId, messageId));
        if (existing != null) {
            store.put(key(tenantId, messageId), new Entry(existing.fingerprint, true, result));
        }
    }

    /**
     * Release an in-flight create record after a failure, so a same-key retry can
     * re-register (NEW) and re-attempt instead of being blocked as IN_FLIGHT. A
     * failed create must not pin the idempotency key. No-op for SKIP (no
     * messageId) and for absent or already-completed records.
     *
     * @param tenantId  authoritative tenant
     * @param messageId create idempotency key
     */
    public void abort(String tenantId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        Entry existing = store.get(key(tenantId, messageId));
        if (existing != null && !existing.completed) {
            store.remove(key(tenantId, messageId));
        }
    }

    /** Clear all idempotency state (test / admin helper). */
    public void clear() {
        store.clear();
    }

    /** Test/observability peek. */
    public Optional<Boolean> isCompleted(String tenantId, String messageId) {
        Entry e = store.get(key(tenantId, messageId));
        return e == null ? Optional.empty() : Optional.of(e.completed);
    }

    private static String key(String tenantId, String messageId) {
        return tenantId + ":" + messageId;
    }
}
