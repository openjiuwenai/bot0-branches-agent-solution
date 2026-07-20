/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.model.DiscoveryQuery;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque pagination token bound to tenant + caller + query fingerprint (0711 §5.1.6).
 */
final class ContinuationTokenCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContinuationTokenCodec() {
    }

    static int decodeOffset(String token, DiscoveryQuery query) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            TokenPayload payload = MAPPER.readValue(json, TokenPayload.class);
            if (!payload.tenantId().equals(query.context().tenantId())
                    || !payload.callerRef().equals(query.context().callerRef())
                    || !payload.queryFingerprint().equals(fingerprint(query))) {
                throw new IllegalArgumentException("continuation token does not match query");
            }
            return payload.offset();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("malformed continuation token", ex);
        }
    }

    static String encode(DiscoveryQuery query, int nextOffset) {
        try {
            TokenPayload payload = new TokenPayload(
                    query.context().tenantId(),
                    query.context().callerRef(),
                    fingerprint(query),
                    nextOffset);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode continuation token", ex);
        }
    }

    private static String fingerprint(DiscoveryQuery query) {
        return Integer.toHexString(java.util.Objects.hash(
                query.agentId(),
                query.serviceId(),
                query.a2aSkillId(),
                query.constraints(),
                query.limit()));
    }

    private record TokenPayload(String tenantId, String callerRef, String queryFingerprint, int offset) {
    }
}
