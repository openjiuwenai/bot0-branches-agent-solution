/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.spec.dto.QueryResponse;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

class AmbiguousPayloadParserTest {
    private static final String AMBIGUOUS_ID = "1";

    private static final String ENVELOPE_JSON_WITH_PAYLOAD = "{\"type\":\"answer\","
            + "\"intent_id\":\"1\","
            + "\"payload\":{\"content\":\"无法确定国内/国际\"}}";

    private static final String ENVELOPE_JSON_TOP_LEVEL = "{\"type\":\"answer\","
            + "\"intent_id\":\"1\","
            + "\"response_content\":\"无法确定\"}";

    @Test
    void parsesFromQueryResponseContentJsonWithPayloadContent() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", ENVELOPE_JSON_WITH_PAYLOAD);
        QueryResponse response = new QueryResponse(result, "conv-1");

        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromQueryResponse(response, AMBIGUOUS_ID);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
        assertThat(parsed.get().responseContent()).isEqualTo("无法确定国内/国际");
        assertThat(parsed.get().ambiguousIntentId()).isEqualTo("1");
    }

    @Test
    void parsesFromQueryResponseContentJsonWithTopLevelResponseContent() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", ENVELOPE_JSON_TOP_LEVEL);
        QueryResponse response = new QueryResponse(result, "conv-1");

        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromQueryResponse(response, AMBIGUOUS_ID);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().responseContent()).isEqualTo("无法确定");
    }

    @Test
    void returnsEmptyWhenIntentIdDoesNotMatch() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", "{\"type\":\"answer\",\"intent_id\":\"intent_L2_hotel\","
                + "\"payload\":{\"content\":\"国内酒店\"}}");
        QueryResponse response = new QueryResponse(result, "conv-1");

        assertThat(AmbiguousPayloadParser.fromQueryResponse(response, AMBIGUOUS_ID)).isEmpty();
    }

    @Test
    void returnsEmptyWhenContentIsNotJson() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", "plain text answer");
        QueryResponse response = new QueryResponse(result, "conv-1");

        assertThat(AmbiguousPayloadParser.fromQueryResponse(response, AMBIGUOUS_ID)).isEmpty();
    }

    @Test
    void returnsEmptyWhenContentMissing() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        QueryResponse response = new QueryResponse(result, "conv-1");

        assertThat(AmbiguousPayloadParser.fromQueryResponse(response, AMBIGUOUS_ID)).isEmpty();
    }

    @Test
    void returnsEmptyForNullQueryResponse() {
        assertThat(AmbiguousPayloadParser.fromQueryResponse(null, AMBIGUOUS_ID)).isEmpty();
    }

    @Test
    void parsesFromChunkDataMap() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("intent_id", "1");
        envelope.put("response_content", "无法确定");
        envelope.put("ambiguous", true);

        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromChunkData(envelope, AMBIGUOUS_ID);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().responseContent()).isEqualTo("无法确定");
    }

    @Test
    void parsesFromChunkDataString() {
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromChunkData(
                ENVELOPE_JSON_WITH_PAYLOAD, AMBIGUOUS_ID);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
    }

    @Test
    void fromThrowableLegacyPathStillParsesMarkerPayload() {
        // Legacy exception-message path retained for future callers that surface
        // ambiguous as an exception. L2→L1 path no longer uses it.
        String payload = "{\"code\":\"VERSATILE_INTENT_AMBIGUOUS\","
                + "\"intent_id\":\"1\",\"response_content\":\"无法确定\","
                + "\"ambiguous_intent_id\":\"1\"}";
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(
                new IllegalStateException("Remote batch execution failed",
                        new RemoteAgentException(payload, null)));
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
    }
}
