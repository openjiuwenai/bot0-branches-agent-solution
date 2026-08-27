/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests the Bus-specific decoration of standard A2A Task subscriptions. */
class BusTaskSubscriptionClientTest {
    @Test
    void mapsStreamReferenceToTransportHeader() {
        var request = BusTaskSubscriptionClient.toA2aRequest(
                new BusTaskSubscriptionClient.SubscriptionRequest(
                        "http://runtime-b:8080", "task-1", "stream-ref-1"));

        assertThat(request.endpointUrl()).isEqualTo("http://runtime-b:8080");
        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.requestHeaders()).containsOnlyKeys(BusTaskSubscriptionClient.STREAM_REFERENCE_HEADER);
        assertThat(request.requestHeaders().get(BusTaskSubscriptionClient.STREAM_REFERENCE_HEADER))
                .isEqualTo("stream-ref-1");
    }
}
