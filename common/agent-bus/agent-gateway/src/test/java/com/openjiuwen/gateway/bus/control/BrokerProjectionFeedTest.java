/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unit tests for {@link BrokerProjectionFeed}: subscribe family + the dispatcher routing model
 * (issue E-layer2) — a background dispatcher drains the shared {@code responseConsumer} and routes
 * each event to its {@code correlationId}'s staging queue; {@link BrokerProjectionFeed#poll} reads
 * its own queue only (decoupled from the shared consumer, no stage-retry).
 *
 * @since 2026-07-27
 */
class BrokerProjectionFeedTest {
    // 20ms poll timeout keeps the empty-poll tests fast (production uses the 500ms default).
    private final FakeBrokerForwardingConsumerPort consumer = new FakeBrokerForwardingConsumerPort();
    private final BrokerProjectionFeed feed = new BrokerProjectionFeed(consumer, "gateway-01", 20L);

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

    @AfterEach
    void stop() {
        // only the subscribe test calls start() (which starts the dispatcher thread) — stop it.
        if (feed.isRunning()) {
            feed.stop();
        }
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
    void drainOnceRoutesMatchingEventAndPollReadsIt() {
        String a2a = b64Url("{\"result\":{\"id\":\"t1\"}}");
        consumer.enqueue(msg("c1", AgentBusEventType.INVOCATION_RESPONSE, "runtime-01",
                "taskId=t1;a2aResponseType=Task;a2aResponse=" + a2a));
        feed.drainOnce(); // dispatcher routes c1's event to c1's staging queue + commits
        Optional<ProjectionFeed.ProjectionEvent> evt = feed.poll("c1");
        assertThat(evt).isPresent();
        assertThat(evt.get().eventType()).isEqualTo(AgentBusEventType.INVOCATION_RESPONSE);
        assertThat(evt.get().taskId()).isEqualTo("t1");
        assertThat(evt.get().body()).isEqualTo("{\"result\":{\"id\":\"t1\"}}");
        assertThat(consumer.committed()).hasSize(1);
    }

    @Test
    void drainOnceRoutesNonMatchingToItsOwnerAndOtherCorrIdPollsEmpty() {
        consumer.enqueue(msg("c2", AgentBusEventType.INVOCATION_RESPONSE, "runtime-01", "taskId=t2"));
        feed.drainOnce(); // routes c2's event to c2's staging (not c1's) + commits
        // c1 has no event — poll reads c1's (empty) staging, does NOT pull c2 from the consumer
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).hasSize(1);
        // the owner (c2) drains its staged event
        Optional<ProjectionFeed.ProjectionEvent> c2 = feed.poll("c2");
        assertThat(c2).isPresent();
        assertThat(c2.get().taskId()).isEqualTo("t2");
    }

    @Test
    void drainOnceSkipsSelfSourceAndCommits() {
        consumer.enqueue(msg("c1", AgentBusEventType.INVOCATION_RESPONSE, "gateway-01", "taskId=t1"));
        feed.drainOnce(); // self-source: commit + skip (not routed to any staging)
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).hasSize(1);
    }

    @Test
    void pollEmptyWhenNoMessage() {
        assertThat(feed.poll("c1")).isEmpty();
        assertThat(consumer.committed()).isEmpty();
    }

    @Test
    void pollReadsOwnStagingAndDoesNotPullOthersFromConsumer() {
        // issue E-layer2: poll(corrId) reads its own per-corrId staging queue — it does NOT pull (and
        // commit) another corrId's event from the shared consumer. The dispatcher / drainOnce owns
        // the shared consumer; servlet threads no longer contend on it (no 14-15s stage-retry).
        consumer.enqueue(msg("c2", AgentBusEventType.INVOCATION_RESPONSE, "runtime-01", "taskId=t2"));
        assertThat(feed.poll("c1")).isEmpty();          // c1 has no event
        assertThat(consumer.committed()).isEmpty();     // poll(c1) did NOT pull c2 — decoupled
    }
}
