/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for G4 {@link IdempotencyRule} (FEAT-011 L2 §3.6 T-G4-1..T-G4-5).
 */
class IdempotencyRuleTest {
    private static final String TENANT = "tenant-1";
    private static final String BODY_A = "{\"a\":1}";
    private static final String BODY_B = "{\"a\":2}";

    private final IdempotencyRule rule = new IdempotencyRule();

    @Test
    void firstCreateRegistersInFlightAndProceeds() {
        IdempotencyRule.Decision d = rule.check(TENANT, "m1", BODY_A);
        assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.NEW);
        assertThat(rule.isCompleted(TENANT, "m1")).contains(false);
    }

    @Test
    void sameKeySameBodyCompletedReplaysPriorResult() {
        rule.check(TENANT, "m2", BODY_A);
        rule.complete(TENANT, "m2", "prior-result");
        IdempotencyRule.Decision d = rule.check(TENANT, "m2", BODY_A);
        assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.REPLAY);
        assertThat(d.result()).isEqualTo("prior-result");
    }

    @Test
    void sameKeyDifferentBodyReturnsConflict() {
        rule.check(TENANT, "m3", BODY_A);
        IdempotencyRule.Decision d = rule.check(TENANT, "m3", BODY_B);
        assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.CONFLICT);
    }

    @Test
    void noMessageIdSkipsDedup() {
        IdempotencyRule.Decision d = rule.check(TENANT, "", BODY_A);
        assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.SKIP);
        assertThat(rule.isCompleted(TENANT, "")).isEmpty();
    }

    @Test
    void sameKeySameBodyInFlightDuplicate() {
        rule.check(TENANT, "m4", BODY_A);
        IdempotencyRule.Decision d = rule.check(TENANT, "m4", BODY_A);
        assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.IN_FLIGHT_DUPLICATE);
    }

    @Test
    void agentIdChangeAltersFingerprint() {
        // BODY_A has no agentId; BODY_A_AGENT adds metadata.agentId -> different fingerprint -> CONFLICT.
        String bodyAgent = BODY_A.replace("}", ",\"metadata\":{\"agentId\":\"x\"}}");
        rule.check(TENANT, "m5", BODY_A);
        assertThat(rule.check(TENANT, "m5", bodyAgent).outcome())
                .isEqualTo(IdempotencyRule.Outcome.CONFLICT);
    }

    @Test
    void abortReleasesInFlightSoNextCheckIsNew() {
        rule.check(TENANT, "m6", BODY_A); // NEW -> IN_FLIGHT
        rule.abort(TENANT, "m6");
        assertThat(rule.check(TENANT, "m6", BODY_A).outcome())
                .isEqualTo(IdempotencyRule.Outcome.NEW);
    }

    @Test
    void abortIsNoOpForSkipAndAbsentAndCompleted() {
        rule.abort(TENANT, "");       // SKIP: no messageId
        rule.abort(TENANT, null);
        rule.abort(TENANT, "absent"); // no record
        rule.check(TENANT, "m7", BODY_A);
        rule.complete(TENANT, "m7", "done");
        rule.abort(TENANT, "m7");     // already completed -> must NOT remove
        assertThat(rule.check(TENANT, "m7", BODY_A).outcome())
                .isEqualTo(IdempotencyRule.Outcome.REPLAY);
    }
}
