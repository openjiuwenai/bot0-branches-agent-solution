/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;

import org.junit.jupiter.api.Test;

import java.util.Optional;

class AmbiguousPayloadParserTest {
    private static final String PAYLOAD = "{\"code\":\"VERSATILE_INTENT_AMBIGUOUS\","
            + "\"intent_id\":\"1\",\"response_content\":\"无法确定国内/国际\","
            + "\"ambiguous_intent_id\":\"1\"}";

    @Test
    void parsesPayloadFromDirectMessage() {
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(
                new IllegalStateException(PAYLOAD));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
        assertThat(parsed.get().responseContent()).isEqualTo("无法确定国内/国际");
        assertThat(parsed.get().ambiguousIntentId()).isEqualTo("1");
    }

    @Test
    void parsesPayloadFromWrappedCause() {
        Throwable cause = new RemoteAgentException(PAYLOAD, null);
        Throwable wrapper = new IllegalStateException("Remote batch execution failed", cause);
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(wrapper);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
    }

    @Test
    void parsesPayloadWithPrefixAndSuffix() {
        Throwable t = new IllegalStateException("Remote agent failed: " + PAYLOAD + " — see logs");
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(t);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().responseContent()).isEqualTo("无法确定国内/国际");
    }

    @Test
    void returnsEmptyForNonAmbiguousMessage() {
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(
                new IllegalStateException("Remote batch execution failed: some other error"));
        assertThat(parsed).isEmpty();
    }

    @Test
    void returnsEmptyForNullThrowable() {
        assertThat(AmbiguousPayloadParser.fromThrowable(null)).isEmpty();
    }

    @Test
    void returnsEmptyWhenJsonMalformed() {
        Throwable t = new IllegalStateException(
                "VERSATILE_INTENT_AMBIGUOUS {not valid json}");
        assertThat(AmbiguousPayloadParser.fromThrowable(t)).isEmpty();
    }
}
