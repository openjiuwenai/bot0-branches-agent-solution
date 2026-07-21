/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.bus.forwarding.test;

import com.openjiuwen.bus.forwarding.runtime.ForwardingStateMachine;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory test double for {@link ForwardingInboxPort} — NON-PRODUCTION.
 *
 * <p>Backed by a {@link HashMap} keyed by {@code (tenantId, messageId,
 * consumerServiceId)} (the inbox dedup key). Validates every transition through
 * {@link ForwardingStateMachine}. Distinct consumers dedup independently;
 * duplicate arrival returns {@code DUPLICATE_SUPPRESSED} without mutating the
 * stored entry.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.2}.
 *
 * @since 0.1.0
 */
// non-production — test fixture only; real persistence is Stage 8
public final class InMemoryForwardingInbox implements ForwardingInboxPort {
    private final Map<Key, Entry> store = new HashMap<>();
    private final ForwardingStateMachine stateMachine = new ForwardingStateMachine();

    private record Key(String tenantId, String messageId, String consumerServiceId) {}

    private record Entry(ForwardingStatus.Inbox status, long receivedAt,
            long consumedAt, ForwardingFailureCode failureCode) {}

    @Override
    public ForwardingStatus.Inbox receive(ForwardingEnvelope envelope,
                                          String consumerServiceId, long nowMillisEpoch) {
        Objects.requireNonNull(envelope, "envelope is required");
        Objects.requireNonNull(consumerServiceId, "consumerServiceId is required");
        if (consumerServiceId.isBlank()) {
            throw new IllegalArgumentException("consumerServiceId must not be blank");
        }
        Key key = new Key(envelope.tenantId(), envelope.messageId().value(), consumerServiceId);
        if (store.containsKey(key)) {
            // duplicate arrival — dedup outcome, do not mutate the stored entry
            return stateMachine.transitInbox(null, ForwardingStateMachine.InboxEvent.ARRIVE_DUPLICATE);
        }
        ForwardingStatus.Inbox status =
                stateMachine.transitInbox(null, ForwardingStateMachine.InboxEvent.ARRIVE_NEW);
        store.put(key, new Entry(status, nowMillisEpoch, 0L, null));
        return status;
    }

    @Override
    public ForwardingStatus.Inbox markConsumed(ForwardingMessageId id, String tenantId,
                                               String consumerServiceId) {
        return mutate(id, tenantId, consumerServiceId,
                ForwardingStateMachine.InboxEvent.CONSUME, null);
    }

    @Override
    public ForwardingStatus.Inbox markRejected(ForwardingMessageId id, String tenantId,
                                               String consumerServiceId, ForwardingFailureCode code) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(consumerServiceId, "consumerServiceId is required");
        Objects.requireNonNull(code, "code is required for markRejected");
        // Mirrors JdbcForwardingInbox's upsert: a poison rejected BEFORE inbox.receive
        // (EventBusRelayWorker.rejectPoison, governance decode/correlation failure) has no
        // prior RECEIVED row, so markRejected INSERTs a REJECTED audit row directly; a prior
        // RECEIVED row is UPDATEd to REJECTED; an already-terminal row is left untouched
        // (idempotent). The next status is computed from RECEIVED + REJECT (always REJECTED).
        ForwardingStatus.Inbox next = stateMachine.transitInbox(
                ForwardingStatus.Inbox.RECEIVED, ForwardingStateMachine.InboxEvent.REJECT);
        Key key = new Key(tenantId, id.value(), consumerServiceId);
        Entry existing = store.get(key);
        if (existing == null) {
            store.put(key, new Entry(next, System.currentTimeMillis(), 0L, code));
        } else if (existing.status() == ForwardingStatus.Inbox.RECEIVED) {
            store.put(key, new Entry(next, existing.receivedAt(), existing.consumedAt(), code));
        } else {
            // fall-through: already-terminal row left untouched (idempotent)
        }
        return next;
    }

    @Override
    public ForwardingStatus.Inbox statusOf(ForwardingMessageId id, String tenantId,
                                           String consumerServiceId) {
        Entry entry = requireEntry(id, tenantId, consumerServiceId);
        return entry.status();
    }

    private ForwardingStatus.Inbox mutate(ForwardingMessageId id, String tenantId,
                                          String consumerServiceId,
                                          ForwardingStateMachine.InboxEvent event,
                                          ForwardingFailureCode code) {
        Entry entry = requireEntry(id, tenantId, consumerServiceId);
        ForwardingStatus.Inbox next = stateMachine.transitInbox(entry.status(), event);
        long consumedAt = (next == ForwardingStatus.Inbox.CONSUMED)
                ? System.currentTimeMillis() : entry.consumedAt();
        store.put(new Key(tenantId, id.value(), consumerServiceId),
                new Entry(next, entry.receivedAt(), consumedAt, code));
        return next;
    }

    private Entry requireEntry(ForwardingMessageId id, String tenantId, String consumerServiceId) {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(consumerServiceId, "consumerServiceId is required");
        Entry entry = store.get(new Key(tenantId, id.value(), consumerServiceId));
        if (entry == null) {
            throw new IllegalStateException(
                    "no inbox entry for tenantId=" + tenantId
                    + " messageId=" + id.value() + " consumerServiceId=" + consumerServiceId);
        }
        return entry;
    }
}
