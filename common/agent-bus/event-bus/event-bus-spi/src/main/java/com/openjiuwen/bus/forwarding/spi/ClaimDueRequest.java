/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

/**
 * Source-scoped batch claim request for
 * {@link ForwardingOutboxClaimPort#claimDue(ClaimDueRequest)}.
 *
 * <p>Bundles the six source-scoped claim inputs (tenant, now, limit, lease owner,
 * lease expiry, source service) into one value object so the
 * {@link ForwardingOutboxClaimPort#claimDue(ClaimDueRequest) source-scoped claimDue}
 * overload stays within the five-parameter method-signature limit (G.MET.01) without
 * dropping any of the claim's required inputs. Every component is a direct replacement
 * for the corresponding positional parameter of the prior signature.
 *
 * @param tenantId             tenant scope of the claim (Rule R-C.c)
 * @param nowMillisEpoch       the claim instant; used to judge due / lease expiry
 * @param limit                max records to claim in this call ({@code > 0})
 * @param leaseOwner           identity of the claiming dispatcher instance
 * @param leaseUntilMillisEpoch instant until which the claim is exclusive
 *                              ({@code > nowMillisEpoch})
 * @param sourceServiceId      only records enqueued by this source service are claimable
 * @since 0.1.0
 */
public record ClaimDueRequest(
        String tenantId,
        long nowMillisEpoch,
        int limit,
        String leaseOwner,
        long leaseUntilMillisEpoch,
        String sourceServiceId) {
}
