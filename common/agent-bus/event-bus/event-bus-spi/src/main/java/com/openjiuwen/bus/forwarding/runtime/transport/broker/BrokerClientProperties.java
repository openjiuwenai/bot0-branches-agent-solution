/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import java.util.Objects;

/**
 * Broker-agnostic connection configuration for a concrete broker adapter
 * (Stage 26, T4 hybrid).
 *
 * <p>Carries only generic, product-neutral connection descriptors — no RocketMQ /
 * Kafka / NATS types (decision §6.2 ① spirit: broker product concepts do not leak
 * outside {@code transport.broker}). A concrete adapter (Stage 27+ RocketMQ PoC)
 * maps these generic fields onto its product-specific client config. {@code namespace}
 * is the tenant-isolation scope (L1 topic-per-tenant / namespace partitioning,
 * Stage 25 §6 tenant defence).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 §6 tenant defence,
 * Stage 26 broker SPI scaffold).
 */
// scope: forwarding transport.broker — generic broker connection config; no product types
public record BrokerClientProperties(String nameserverEndpoints, String namespace) {
    public BrokerClientProperties {
        requireNonBlank(nameserverEndpoints, "nameserverEndpoints");
        requireNonBlank(namespace, "namespace");
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
