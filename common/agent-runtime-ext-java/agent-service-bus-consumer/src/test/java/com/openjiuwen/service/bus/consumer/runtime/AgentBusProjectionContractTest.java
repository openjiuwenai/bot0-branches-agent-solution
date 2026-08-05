/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
    void acceptedProjectionMatchesGoldenDescriptor() {
        BusResponseProjection projection = projection("A2A_CALL_ACCEPTED", "task-1", "ACCEPTED",
                Map.of("idempotencyResult", "NEW"));

        assertThat(AgentBusResponsePublisher.encodeProjection(projection)).isEqualTo(
                "taskId=task-1;projectionKind=ACCEPTED;revision=0;idempotencyResult=NEW");
    }

    @Test
    void scalarDataUsesCanonicalKeyOrder() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retryable", false);
        data.put("errorCode", "TASK_NOT_FOUND");
        BusResponseProjection projection = projection("A2A_CALL_FAILED", null, "FAILED", data);

        assertThat(AgentBusResponsePublisher.encodeProjection(projection)).isEqualTo(
                "projectionKind=FAILED;revision=0;reason=TASK_NOT_FOUND;"
                        + "errorCode=TASK_NOT_FOUND;retryable=false");
    }

    private static BusResponseProjection projection(String eventType, String taskId, String kind,
            Map<String, Object> data) {
        return new BusResponseProjection("event-1", eventType, "tenant-a", "correlation-1", taskId,
                Instant.EPOCH, data, "trace-1", "runtime-b", "runtime-a", "route-b",
                "idempotency-1", "request-1", kind, 0L);
    }
}
