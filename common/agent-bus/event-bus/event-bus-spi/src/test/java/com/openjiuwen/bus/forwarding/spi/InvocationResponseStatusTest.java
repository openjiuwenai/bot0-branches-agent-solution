/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Pins the seven gateway-side observed invocation statuses of
 * {@link InvocationResponseStatus} (FEAT-013 §2.3.3, shared by FEAT-014 for
 * the remote-Task viewpoint; FEAT-017 adds INPUT_REQUIRED).
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §2.3.3};
 * {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-014-a2a-call-event-forwarding.md §2.3.2};
 * {@code version-scope/FEAT-017-bus-event-subscription-consumption.md §5.1}.
 */
class InvocationResponseStatusTest {
    @Test
    void has_seven_observed_statuses() {
        assertThat(EnumSet.allOf(InvocationResponseStatus.class)).containsExactlyInAnyOrder(
                InvocationResponseStatus.COMPLETED_RESPONSE,
                InvocationResponseStatus.ACCEPTED_WITH_TASK,
                InvocationResponseStatus.INPUT_REQUIRED,
                InvocationResponseStatus.STREAM_READY,
                InvocationResponseStatus.REJECTED,
                InvocationResponseStatus.FAILED,
                InvocationResponseStatus.UNKNOWN);
    }

    @Test
    void value_of_rejects_an_unknown_status() {
        assertThatThrownBy(() -> InvocationResponseStatus.valueOf("NOT_A_REAL_STATUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void includes_input_required_status_for_feat_017() {
        // FEAT-017 mandates INPUT_REQUIRED be a published bus response event projection; the
        // gateway / caller runtime observed-status must therefore carry an INPUT_REQUIRED
        // state to map *_INPUT_REQUIRED events onto (FEAT-017 P-07 "调用方状态机").
        assertThat(InvocationResponseStatus.values())
                .extracting(Enum::name)
                .contains("INPUT_REQUIRED");
        assertThat(InvocationResponseStatus.values()).hasSize(7);
    }
}
