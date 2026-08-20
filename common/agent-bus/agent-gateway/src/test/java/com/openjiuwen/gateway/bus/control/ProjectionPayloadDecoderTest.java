/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import org.junit.jupiter.api.Test;

class ProjectionPayloadDecoderTest {
    private final ProjectionPayloadDecoder decoder = new ProjectionPayloadDecoder();

    @Test
    void decodesEmbeddedJsonRpcResponseWithoutRebuildingIt() {
        String payload = projection("RESPONSE", "\"taskId\":\"task-1\",\"a2aResponse\":{"
                + "\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"result\":{\"task\":{"
                + "\"id\":\"task-1\"}}}");

        ProjectionPayloadDecoder.DecodedProjection decoded = decoder.decode(payload,
                AgentBusEventType.INVOCATION_RESPONSE);

        assertThat(decoded.taskId()).isEqualTo("task-1");
        assertThat(decoded.a2aResponsePresent()).isTrue();
        assertThat(decoded.body()).contains("\"id\":9007199254740993");
    }

    @Test
    void rejectsLegacyDescriptorAndDuplicateKeys() {
        assertThatThrownBy(() -> decoder.decode("taskId=legacy", AgentBusEventType.INVOCATION_RESPONSE))
                .isInstanceOf(ProjectionProtocolException.class);
        String duplicate = "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"ACCEPTED\","
                + "\"revision\":0,\"taskId\":\"one\",\"taskId\":\"two\","
                + "\"idempotencyResult\":\"NEW\"}";
        assertThatThrownBy(() -> decoder.decode(duplicate, AgentBusEventType.INVOCATION_ACCEPTED))
                .isInstanceOf(ProjectionProtocolException.class);
    }

    @Test
    void rejectsUnsupportedMajorKindMismatchAndTaskMismatch() {
        assertThatThrownBy(() -> decoder.decode(
                "{\"schemaVersion\":\"2.0\",\"projectionKind\":\"ACCEPTED\",\"revision\":0,"
                        + "\"taskId\":\"task-1\",\"idempotencyResult\":\"NEW\"}",
                AgentBusEventType.INVOCATION_ACCEPTED)).isInstanceOf(ProjectionProtocolException.class);
        assertThatThrownBy(() -> decoder.decode(
                projection("ACCEPTED", "\"taskId\":\"task-1\",\"idempotencyResult\":\"NEW\""),
                AgentBusEventType.INVOCATION_RESPONSE)).isInstanceOf(ProjectionProtocolException.class);
        assertThatThrownBy(() -> decoder.decode(
                projection("RESPONSE", "\"taskId\":\"task-1\",\"a2aResponse\":{\"jsonrpc\":\"2.0\","
                        + "\"id\":\"request-1\",\"result\":{\"task\":{\"id\":\"task-2\"}}}"),
                AgentBusEventType.INVOCATION_RESPONSE)).isInstanceOf(ProjectionProtocolException.class);
    }

    @Test
    void rejectsJsonRpcResponseWithBothResultAndError() {
        String payload = projection("RESPONSE", "\"a2aResponse\":{\"jsonrpc\":\"2.0\","
                + "\"id\":\"request-1\",\"result\":{},\"error\":{\"code\":-32603}}" );

        assertThatThrownBy(() -> decoder.decode(payload, AgentBusEventType.INVOCATION_RESPONSE))
                .isInstanceOf(ProjectionProtocolException.class);
    }

    @Test
    void rejectsOversizedProjection() {
        String payload = projection("FAILED", "\"errorCode\":\"TOO_LARGE\",\"retryable\":false,"
                + "\"extension\":\"" + "x".repeat(70_000) + "\"");

        assertThatThrownBy(() -> decoder.decode(payload, AgentBusEventType.INVOCATION_FAILED))
                .isInstanceOf(ProjectionProtocolException.class)
                .hasMessageContaining("64 KiB");
    }

    private static String projection(String kind, String fields) {
        return "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"" + kind
                + "\",\"revision\":0," + fields + "}";
    }
}
