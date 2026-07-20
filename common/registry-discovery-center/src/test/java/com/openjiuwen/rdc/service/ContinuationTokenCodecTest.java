/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.RegistryRequestContext;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class ContinuationTokenCodecTest {
    @Test
    void encode_decode_round_trip() {
        DiscoveryQuery query = sampleQuery("tenant-a", "caller-a", "agent-1");
        String token = ContinuationTokenCodec.encode(query, 5);
        assertThat(ContinuationTokenCodec.decodeOffset(token, query)).isEqualTo(5);
    }

    @Test
    void blank_token_decodes_to_zero() {
        DiscoveryQuery query = sampleQuery("tenant-a", "caller-a", "agent-1");
        assertThat(ContinuationTokenCodec.decodeOffset(null, query)).isZero();
        assertThat(ContinuationTokenCodec.decodeOffset("  ", query)).isZero();
    }

    @Test
    void token_mismatch_rejected() {
        DiscoveryQuery original = sampleQuery("tenant-a", "caller-a", "agent-1");
        String token = ContinuationTokenCodec.encode(original, 3);
        DiscoveryQuery otherCaller = sampleQuery("tenant-a", "other-caller", "agent-1");
        assertThatThrownBy(() -> ContinuationTokenCodec.decodeOffset(token, otherCaller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("continuation token");
    }

    private static DiscoveryQuery sampleQuery(String tenantId, String callerRef, String agentId) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                tenantId, callerRef, "trace", "req", Instant.now().plusSeconds(30));
        return DiscoveryQuery.builder()
                .context(ctx)
                .agentId(agentId)
                .constraints(DiscoveryConstraints.none())
                .limit(10)
                .build();
    }
}
