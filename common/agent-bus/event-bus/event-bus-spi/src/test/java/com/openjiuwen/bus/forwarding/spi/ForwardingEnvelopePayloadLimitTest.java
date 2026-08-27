/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Size-cap contract test for {@link ForwardingEnvelope#inlinePayload()} (P-06 §2b
 * bounded-inline guardrail). The compact constructor rejects an {@code inlinePayload}
 * whose UTF-8 byte length exceeds {@link ForwardingEnvelope#MAX_INLINE_PAYLOAD_BYTES},
 * accepts one at or under the cap, accepts {@code null}, and rejects blank. A multi-byte
 * UTF-8 case (U3) confirms the cap counts UTF-8 <em>bytes</em>, not {@code char}s.
 *
 * <p>Authority: {@code event-bus 实施修改方案 v1 §5.1} (U1 / U3).
 */
class ForwardingEnvelopePayloadLimitTest {
    private static final String TENANT = "tenant-a";
    private static final ForwardingRouteHandle ROUTE = new ForwardingRouteHandle("route-1", TENANT);

    /**
     * DATA_BEARING envelope carrying ONLY an inline body (no payloadRef) — the body is the data,
     * so it is the field under test.
     *
     * @param inlinePayload the inline body under test
     * @return a DATA_BEARING envelope with the given inline body and no payloadRef
     */
    private static ForwardingEnvelope dataBearingInline(String inlinePayload) {
        return new ForwardingEnvelope(
                new ForwardingMessageId("msg-pl"),
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                TENANT, "trace-pl", "corr-pl", "idem-pl",
                ROUTE, "cap-pl", "src-pl", "tgt-pl", Long.MAX_VALUE,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, null, inlinePayload);
    }

    @Test
    void inline_payload_at_or_under_max_bytes_is_accepted() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        // exactly MAX bytes (ASCII = 1 byte/char) — the boundary value is accepted (≤).
        String atMax = "a".repeat(max);
        assertThat(atMax.getBytes(UTF_8)).hasSize(max);
        ForwardingEnvelope e = dataBearingInline(atMax);
        assertThat(e.inlinePayload()).isEqualTo(atMax);
    }

    @Test
    void inline_payload_over_max_bytes_is_rejected() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        String overMax = "a".repeat(max + 1);
        assertThat(overMax.getBytes(UTF_8)).hasSize(max + 1);
        assertThatThrownBy(() -> dataBearingInline(overMax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload exceeds max inline size")
                .hasMessageContaining(String.valueOf(max));
    }

    @Test
    void inline_null_accepted_when_payload_ref_carries_data() {
        // DATA_BEARING satisfies the data requirement via payloadRef; a null inlinePayload is valid.
        ForwardingEnvelope e = new ForwardingEnvelope(
                new ForwardingMessageId("msg-ref"),
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                TENANT, "trace-ref", "corr-ref", "idem-ref",
                ROUTE, "cap-ref", "src-ref", "tgt-ref", Long.MAX_VALUE,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, "ref://payload/123", null);
        assertThat(e.inlinePayload()).isNull();
        assertThat(e.payloadRef()).isEqualTo("ref://payload/123");
    }

    @Test
    void inline_payload_blank_is_rejected_as_a_wiring_error() {
        // A non-null blank inlinePayload is a wiring error: null means absent, non-blank means a body.
        // CONTROL_ONLY is used so the DATA_BEARING data-presence check is NOT what rejects it — the
        // blank check itself fires ("must be null or non-blank"), independent of the payload policy.
        assertThatThrownBy(() -> new ForwardingEnvelope(
                new ForwardingMessageId("msg-blank"),
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                TENANT, "trace-blank", "corr-blank", "idem-blank",
                ROUTE, "cap-blank", "src-blank", "tgt-blank", Long.MAX_VALUE,
                ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY, null, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload")
                .hasMessageContaining("null or non-blank");
    }

    @Test
    void size_cap_counts_utf8_bytes_not_chars_for_multibyte_payloads() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        // '字' (U+5B57) is 3 UTF-8 bytes. Pick a char count STRICTLY UNDER max so a char-counting
        // guard would accept it, but whose UTF-8 byte count EXCEEDS max — proving the cap counts
        // bytes, not chars.
        int chars = (max / 3) + 1;
        String payload = "字".repeat(chars);
        assertThat(payload.length()).isLessThan(max);                  // char count under the cap
        assertThat(payload.getBytes(UTF_8).length).isGreaterThan(max); // byte count over the cap
        assertThatThrownBy(() -> dataBearingInline(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inlinePayload exceeds max inline size");
    }

    @Test
    void multibyte_payload_at_exactly_max_bytes_is_accepted() {
        int max = ForwardingEnvelope.MAX_INLINE_PAYLOAD_BYTES;
        // Build a multi-byte payload of EXACTLY max bytes: fill with 3-byte '字', then pad with
        // 1-byte ASCII so the byte total hits max precisely (max = 3 * (max / 3) + (max % 3)).
        int threes = max / 3;
        int pad = max - threes * 3;
        StringBuilder sb = new StringBuilder(threes + pad);
        sb.append("字".repeat(threes));
        for (int i = 0; i < pad; i++) {
            sb.append('a');
        }
        String atMax = sb.toString();
        assertThat(atMax.getBytes(UTF_8)).hasSize(max);
        ForwardingEnvelope e = dataBearingInline(atMax);
        assertThat(e.inlinePayload()).isEqualTo(atMax);
    }

    // ---- capability length contract (#161): char count, mirrors JDBC outbox VARCHAR(128) ----

    /**
     * DATA_BEARING envelope (data via payloadRef) carrying a variable {@code capability} — the
     * capability is the field under test.
     *
     * @param capability the capability identifier under test
     * @return a DATA_BEARING envelope with the given capability
     */
    private static ForwardingEnvelope withCapability(String capability) {
        return new ForwardingEnvelope(
                new ForwardingMessageId("msg-cap"),
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                TENANT, "trace-cap", "corr-cap", "idem-cap",
                ROUTE, capability, "src-cap", "tgt-cap", Long.MAX_VALUE,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, "ref://payload/cap", null);
    }

    @Test
    void capability_at_max_chars_is_accepted() {
        int max = ForwardingEnvelope.MAX_CAPABILITY_CHARS;
        String atMax = "a".repeat(max);
        assertThat(atMax.length()).isEqualTo(max);
        ForwardingEnvelope e = withCapability(atMax);
        assertThat(e.capability()).isEqualTo(atMax);
    }

    @Test
    void capability_over_max_chars_is_rejected() {
        int max = ForwardingEnvelope.MAX_CAPABILITY_CHARS;
        String overMax = "a".repeat(max + 1);
        assertThatThrownBy(() -> withCapability(overMax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability exceeds max")
                .hasMessageContaining(String.valueOf(max));
    }

    @Test
    void capability_cap_counts_chars_not_utf8_bytes_for_multibyte() {
        int max = ForwardingEnvelope.MAX_CAPABILITY_CHARS;
        // '字' (U+5B57) is 3 UTF-8 bytes but 1 char (BMP). A max-char '字' string is max chars
        // (≤ cap, accepted) but 3*max bytes — proving the cap counts chars (String.length), matching
        // the JDBC outbox VARCHAR(128) char semantics, not UTF-8 bytes (unlike inlinePayload).
        String atMaxChars = "字".repeat(max);
        assertThat(atMaxChars.length()).isEqualTo(max);
        assertThat(atMaxChars.getBytes(UTF_8).length).isGreaterThan(max);
        ForwardingEnvelope e = withCapability(atMaxChars);
        assertThat(e.capability()).isEqualTo(atMaxChars);
    }
}
