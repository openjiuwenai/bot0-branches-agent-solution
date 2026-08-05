/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.spi.ingress;

import java.util.Objects;
import java.util.UUID;

/**
 * Bus acknowledgement envelope returned to the client by
 * {@link IngressGateway#routeClientRequest(IngressEnvelope)}.
 *
 * <p>Schema authority: {@code docs/contracts/ingress-envelope.v1.yaml#response}.
 * Long-running work returns {@link IngressStatus#ACCEPTED} with a
 * {@code cursor} populated per Rule R-F (Cursor Flow). Refused traffic
 * returns {@link IngressStatus#REJECTED} carrying a {@code rejectionReason}.
 * Bus-level deferrals (e.g. backpressure) return {@link IngressStatus#DEFERRED}.
 *
 * <p>Authority: ADR-0089; CLAUDE.md Rule R-I sub-clause .b.
 */
public record IngressResponse(
        UUID requestId,            // mirrors IngressEnvelope.requestId
        IngressStatus status,
        String cursor,             // present iff status == ACCEPTED && IngressEnvelope.requestType == RUN_CREATE
        String rejectionReason     // present iff status == REJECTED
) {
    public IngressResponse {
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(status, "status is required");
        if (status == IngressStatus.REJECTED) {
            Objects.requireNonNull(rejectionReason, "rejectionReason is required when status=REJECTED");
            if (rejectionReason.isBlank()) {
                throw new IllegalArgumentException("rejectionReason must not be blank when status=REJECTED");
            }
        }
    }

    /**
     * Build an ACCEPTED response carrying the client cursor for a long-running
     * RUN_CREATE request.
     *
     * @param requestId the mirrored client request id
     * @param cursor the cursor to return (present iff status == ACCEPTED)
     * @return an accepted response with the given cursor
     */
    public static IngressResponse accepted(UUID requestId, String cursor) {
        return new IngressResponse(requestId, IngressStatus.ACCEPTED, cursor, null);
    }

    /**
     * Build a REJECTED response carrying a non-blank rejection reason.
     *
     * @param requestId the mirrored client request id
     * @param reason the non-blank rejection reason (required when status=REJECTED)
     * @return a rejected response with the given reason
     */
    public static IngressResponse rejected(UUID requestId, String reason) {
        return new IngressResponse(requestId, IngressStatus.REJECTED, null, reason);
    }

    /**
     * Build a DEFERRED response signalling bus-level backpressure (the client
     * should retry the same request).
     *
     * @param requestId the mirrored client request id
     * @return a deferred response with no cursor and no rejection reason
     */
    public static IngressResponse deferred(UUID requestId) {
        return new IngressResponse(requestId, IngressStatus.DEFERRED, null, null);
    }

    /** Closed enum mirroring ingress-envelope.v1.yaml#response.required_fields.status. */
    public enum IngressStatus {
        ACCEPTED, REJECTED, DEFERRED
    }
}
