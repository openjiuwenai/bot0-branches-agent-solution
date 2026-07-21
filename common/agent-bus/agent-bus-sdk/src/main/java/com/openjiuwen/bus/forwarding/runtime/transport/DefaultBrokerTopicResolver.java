/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.Objects;

/**
 * Default {@link BrokerTopicResolver} — convention-based topic derivation from
 * {@link AgentBusEventType} + hop suffix (FEAT-013/014, arch-driven G5-B; Option B).
 *
 * <p>Maps each event type to a logical route family:
 * <ul>
 *   <li>{@value #FAMILY_INVOCATION} — FEAT-013 client-invocation family
 *       ({@code CLIENT_*} and {@code INVOCATION_*} event types).</li>
 *   <li>{@value #FAMILY_A2A} — FEAT-014 service-to-service family
 *       ({@code A2A_*} event types, both {@code A2A_CALL_*} and
 *       {@code A2A_STREAM_*}).</li>
 * </ul>
 * The topic is {@code ascend_bus_<routeFamily>_<suffix>} (L2 §5.2). Deriving the
 * topic from the event type — not from {@code routeHandle.value()} — lets the
 * opaque {@code routeHandle} become a true registry handle ({@code v2:...}) that
 * rides the envelope end-to-end as a passthrough field on the T4 pub/sub path
 * (it is unwrapped only by the gateway's T1 SSE bridge, out of agent-bus).
 *
 * <p>Stateless and thread-safe; the {@code suffix} is supplied per call so a
 * single resolver instance serves every hop role ({@code req} / {@code deliver} /
 * {@code resp_in} / {@code resp_out}). Plain JDK — no Spring / broker types
 * (forwarding purity preserved).
 *
 * <p>Replaces the prior {@code BrokerTopicResolver} implementation class (which
 * implemented {@link ForwardingEndpointResolver} and derived the topic from
 * {@code routeHandle.value()}). That derivation coupled the topic to a logical
 * route name carried in the handle, preventing the handle from going opaque.
 *
 * @since 0.1.0
 */
// scope: forwarding transport — pure-Java convention topic resolver (no Spring/broker types)
public final class DefaultBrokerTopicResolver implements BrokerTopicResolver {
    /** FEAT-013 client-invocation route family ({@code CLIENT_*} / {@code INVOCATION_*} event types). */
    public static final String FAMILY_INVOCATION = "invocation";
    /** FEAT-014 service-to-service route family ({@code A2A_*} event types). */
    public static final String FAMILY_A2A = "a2a";

    @Override
    public String resolveTopic(AgentBusEventType eventType, String suffix) {
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(suffix, "suffix is required");
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("suffix must not be blank");
        }
        return TOPIC_PREFIX + routeFamily(eventType) + "_" + suffix;
    }

    /**
     * Map an event type to its logical route family ({@value #FAMILY_A2A} for
     * {@code A2A_*}, else {@value #FAMILY_INVOCATION}).
     *
     * @param eventType the event type (non-null)
     * @return the route family segment used in the topic name
     */
    public static String routeFamily(AgentBusEventType eventType) {
        Objects.requireNonNull(eventType, "eventType is required");
        return eventType.name().startsWith("A2A_") ? FAMILY_A2A : FAMILY_INVOCATION;
    }
}
