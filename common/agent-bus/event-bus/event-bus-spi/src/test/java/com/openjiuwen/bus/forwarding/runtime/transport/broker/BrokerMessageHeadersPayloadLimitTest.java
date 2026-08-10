/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;

import org.junit.jupiter.api.Test;

/**
 * Size-cap contract test for {@link BrokerMessageHeaders#inlinePayload()} (P-06 §2b
 * wire-layer guardrail). The compact constructor rejects an {@code inlinePayload} whose
 * UTF-8 byte length exceeds {@link ForwardingEnvelope#MAX_INLINE_PAYLOAD_BYTES}, accepts
 * one at or under the cap, accepts {@code null}, and rejects blank. Mirrors the envelope's
 * logic-layer check so the same cap holds at both layers (belt-and-suspenders).
 *
 * <p>Authority: {@code event-bus 实施修改方案 v1 §5.1} (U2).
 */
class BrokerMessageHeadersPayloadLimitTest {
    /**
     * Minimal valid headers carrying an inline body (control-plane fields populated, no payloadRef).
     *
     * @param inlinePayload the inline body under test
     * @return a {@link BrokerMessageHeaders} with the given inline body
     */
    private static BrokerMessageHeaders headersWithInline(String inlinePayload) {
        return new BrokerMessageHeaders(
                "tenant-a", "msg-1", "src-1", "tgt-1",
                null,
                "corr-1",
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                "trace-1", "idem-1", "route-1", "cap-1",
                Long.MAX_VALUE,
                inlinePayload,
                null);
    }

    @Test
    void inline_payload_at_or_under_max_bytes_is_accepted() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        String atMax = "a".repeat(max);
        assertThat(atMax.getBytes(UTF_8)).hasSize(max);
        BrokerMessageHeaders h = headersWithInline(atMax);
        assertThat(h.inlinePayload()).isEqualTo(atMax);
    }

    @Test
    void inline_payload_over_max_bytes_is_rejected() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        String overMax = "a".repeat(max + 1);
        assertThat(overMax.getBytes(UTF_8)).hasSize(max + 1);
        assertThatThrownBy(() -> headersWithInline(overMax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload exceeds max inline size")
                .hasMessageContaining(String.valueOf(max));
    }

    @Test
    void inline_payload_null_is_accepted() {
        BrokerMessageHeaders h = headersWithInline(null);
        assertThat(h.inlinePayload()).isNull();
    }

    @Test
    void inline_payload_blank_is_rejected_as_a_wiring_error() {
        assertThatThrownBy(() -> headersWithInline("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload")
                .hasMessageContaining("null or non-blank");
    }

    @Test
    void size_cap_counts_utf8_bytes_not_chars_for_multibyte_payloads() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        // '字' (U+5B57) is 3 UTF-8 bytes. Char count under the cap, byte count over the cap —
        // rejecting it proves the wire-layer check counts UTF-8 bytes, not chars.
        int chars = (max / 3) + 1;
        String payload = "字".repeat(chars);
        assertThat(payload.length()).isLessThan(max);
        assertThat(payload.getBytes(UTF_8).length).isGreaterThan(max);
        assertThatThrownBy(() -> headersWithInline(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload exceeds max inline size");
    }
}
