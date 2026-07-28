/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BrokerProjectionFeed}: subscribe family + poll/match/map/commit on the
 * SDK {@code responseConsumer} (FEAT-012 §4.5 I-04 inbound + §4.6 fold).
 *
 * @since 2026-07-27
 */
class BrokerProjectionFeedTest {
    private final FakeBrokerForwardingConsumerPort consumer = new FakeBrokerForwardingConsumerPort();
    private final BrokerProjectionFeed feed = new BrokerProjectionFeed(consumer, "gateway-01");

    private static String b64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static BrokerInboundMessage msg(String correlationId, AgentBusEventType type,
                                            String source, String inlinePayload) {
        return new BrokerInboundMessage("tenant-a", "msg-" + correlationId, source, "gateway-01",
                "gateway-01", null, correlationId, type, "trace-1", "idem-1", "route-1", "cap",
                Long.MAX_VALUE, inlinePayload, null);
    }

    @Test
    void subscribesToInvocationFamilyWithTargetServiceFilterAtStart() {
        feed.start();
        Set<AgentBusEventType> types = new HashSet<>();
        for (FakeBrokerForwardingConsumerPort.Subscription s : consumer.subscriptions()) {
            assertThat(s.consumerServiceId()).isEqualTo("gateway-01");
            assertThat(s.filter().requiredProperties())
                    .isEqualTo(Map.of("targetServiceId", "gateway-01"));
            types.add(s.eventType());
        }
        assertThat(types).containsExactlyInAnyOrder(
                AgentBusEventType.INVOCATION_ACCEPTED,
                AgentBusEventType.INVOCATION_REJECTED,
                AgentBusEventType.INVOCATION_FAILED,
                AgentBusEventType.INVOCATION_RESPONSE,
                AgentBusEventType.INVOCATION_INPUT_REQUIRED,
                AgentBusEventType.INVOCATION_STREAM_READY,
                AgentBusEventType.INVOCATION_TERMINAL);
        // G15: the gateway folds INVOCATION_* only — A2A_CALL_* is caller-runtime (FEAT-005).
        assertThat(types).noneMatch(t -> t.name().startsWith("A2A_"));
    }

    @Test
    void pollMatchesByCorrelationIdMapsAndCommits() {
        String a2a = b64Url("{\"result\":{\"id\":\"t1\"}}");
        consumer.enqueue(msg("c1", AgentBusEventType.INVOCATION_RESPONSE, "runtime-01",
                "taskId=t1;a2aResponseType=Task;a2aResponse=" + a2a));
        Optional<ProjectionFeed.ProjectionEvent> evt = feed.poll("c1");
        assertThat(evt).isPresent();
        assertThat(evt.get().eventType()).isEqualTo(AgentBusEventType.INVOCATION_RESPONSE);
        assertThat(evt.get().taskId()).isEqualTo("t1");
        assertThat(evt.get().body()).isEqualTo("{\"result\":{\"id\":\"t1\"}}");
        assertThat(consumer.committed()).hasSize(1);
    }

    @Test
    void pollSkipsNonMatchingCorrelationAndCommits() {
        consumer.enqueue(msg("c2", AgentBusEventType.INVOCATION_RESPONSE, "runtime-01", "taskId=t2"));
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).hasSize(1);
    }

    @Test
    void pollSkipsSelfSourceAndCommits() {
        consumer.enqueue(msg("c1", AgentBusEventType.INVOCATION_RESPONSE, "gateway-01", "taskId=t1"));
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).hasSize(1);
    }

    @Test
    void pollEmptyWhenNoMessage() {
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).isEmpty();
    }
}
