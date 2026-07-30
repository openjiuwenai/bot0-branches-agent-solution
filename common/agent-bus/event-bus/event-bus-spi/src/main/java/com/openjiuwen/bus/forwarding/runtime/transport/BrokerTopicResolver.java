/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

/**
 * Broker topic derivation port — derives a broker topic name from an
 * {@link AgentBusEventType} + hop {@code suffix}, decoupled from the opaque
 * {@code routeHandle} (FEAT-013/014 registry-discovery-center integration, Option B).
 *
 * <p><b>Why this exists.</b> The T4 pub/sub main path must NOT depend on
 * {@code registry-discovery-center}: the topic is a bus-internal topology fact
 * derived by convention from the event family, while the opaque
 * {@code routeHandle} rides the envelope end-to-end as a passthrough field
 * (unwrapped only by the gateway's T1 SSE bridge, which is out of agent-bus).
 * Deriving the topic from {@code AgentBusEventType} (not from
 * {@code routeHandle.value()}) lets {@code routeHandle.value()} become a true
 * opaque registry handle ({@code v2:...}) without breaking topic routing.
 *
 * <p><b>Event family → topic segment.</b> The resolver maps each
 * {@link AgentBusEventType} to a logical route family ({@code invocation} for
 * the FEAT-013 client-invocation family — {@code CLIENT_*} / {@code INVOCATION_*};
 * {@code a2a} for the FEAT-014 service-to-service family — {@code A2A_*}). The
 * {@code suffix} selects the hop ({@code req} gateway-produce /
 * {@code deliver} event-bus-forward-produce / {@code resp_in}
 * event-bus-response-consume / {@code resp_out} gateway-response-consume). The
 * default convention is {@code ascend_bus_<routeFamily>_<suffix>}.
 *
 * <p><b>agent-bus own responsibility.</b> This port is agent-bus-internal topic
 * derivation — it has no dependency on {@code registry-discovery-center} and
 * duplicates no registry interface. The gateway's discovery / T1 endpoint
 * resolution uses {@link ForwardingEndpointResolver} (a separate port) instead.
 *
 * <p>Plain JDK type — no Spring / JDBC / broker dependency (forwarding purity).
 *
 * @since 0.1.0
 */
// scope: forwarding transport — broker topic derivation port (eventType-driven, opaque-handle-decoupled)
public interface BrokerTopicResolver {
    /** The L2 §5.2 topic-name prefix shared by every agent-bus broker topic. */
    String TOPIC_PREFIX = "ascend_bus_";

    /**
     * Derive the broker topic name for an event type + hop suffix.
     *
     * @param eventType the event type (determines the route family; non-null)
     * @param suffix    the hop suffix ({@code req} / {@code deliver} / {@code resp_in} / {@code resp_out}); non-blank
     * @return the topic name (e.g. {@code ascend_bus_invocation_req}); never null
     */
    String resolveTopic(AgentBusEventType eventType, String suffix);
}
