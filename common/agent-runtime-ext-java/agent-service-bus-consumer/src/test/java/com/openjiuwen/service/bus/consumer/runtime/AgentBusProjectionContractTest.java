/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Golden vectors for the FEAT-017 response projection peer contract.
 *
 * @since 2026-08-04
 */
class AgentBusProjectionContractTest {
    @Test
    void acceptedProjectionMatchesGoldenJson() {
        BusResponseProjection projection = projection("A2A_CALL_ACCEPTED", "task-1", "ACCEPTED",
                Map.of("idempotencyResult", "NEW"));

        assertThat(AgentBusResponsePublisher.encodeProjection(projection)).isEqualTo(
                "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"ACCEPTED\",\"revision\":0,"
                        + "\"taskId\":\"task-1\",\"idempotencyResult\":\"NEW\"}");
    }

    @Test
    void scalarDataUsesCanonicalKeyOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retryable", false);
        data.put("errorCode", "TASK_NOT_FOUND");
        BusResponseProjection projection = projection("A2A_CALL_FAILED", null, "FAILED", data);

        assertThat(AgentBusResponsePublisher.encodeProjection(projection)).isEqualTo(
                "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"FAILED\",\"revision\":0,"
                        + "\"errorCode\":\"TASK_NOT_FOUND\",\"retryable\":false}");
    }

    @Test
    void completeJsonRpcResponseMatchesGoldenJson() {
        String response = "{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"task\":{"
                + "\"id\":\"task-1\"}}}";
        BusResponseProjection projection = projection("A2A_CALL_RESPONSE", "task-1", "RESPONSE",
                Map.of("a2aResponse", response));

        assertThat(AgentBusResponsePublisher.encodeProjection(projection)).isEqualTo(
                "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"RESPONSE\",\"revision\":0,"
                        + "\"taskId\":\"task-1\",\"a2aResponse\":{"
                        + "\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{"
                        + "\"task\":{\"id\":\"task-1\"}}}}" );
    }

    @Test
    void rejectsDuplicateFieldsAndMismatchedTaskIds() {
        var codec = new com.openjiuwen.service.bus.consumer.projection.AgentBusProjectionJsonCodec();
        String duplicate = "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"ACCEPTED\","
                + "\"revision\":0,\"taskId\":\"one\",\"taskId\":\"two\","
                + "\"idempotencyResult\":\"NEW\"}";
        String mismatch = "{\"schemaVersion\":\"1.0\",\"projectionKind\":\"RESPONSE\","
                + "\"revision\":0,\"taskId\":\"task-1\",\"a2aResponse\":{\"jsonrpc\":\"2.0\","
                + "\"id\":\"request-1\",\"result\":{\"task\":{\"id\":\"task-2\"}}}}";

        assertThatThrownBy(() -> codec.decode(duplicate, "A2A_CALL_ACCEPTED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROJECTION_PAYLOAD_INVALID");
        assertThatThrownBy(() -> codec.decode(mismatch, "A2A_CALL_RESPONSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROJECTION_PAYLOAD_INVALID");
    }

    private static BusResponseProjection projection(String eventType, String taskId, String kind,
            Map<String, Object> data) {
        return new BusResponseProjection("event-1", eventType, "tenant-a", "correlation-1", taskId,
                Instant.EPOCH, data, "trace-1", "runtime-b", "runtime-a", "route-b",
                "idempotency-1", "request-1", kind, 0L);
    }
}
