/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone entry point for the event-bus two-hop governance relay
 * (FEAT-013/014 forwarding relay: inbox-dedup + tenant check + correlation
 * match + audit between the produce/consume hops).
 *
 * <p>Run with the {@code eventbus} profile to activate the relay wiring:
 * <pre>{@code
 *   java -jar agent-bus-relay-*.jar --spring.profiles.active=eventbus
 * }</pre>
 * Requires a Postgres datasource (forwarding outbox/inbox) and a RocketMQ
 * nameserver (broker produce/consume) — all env-overridable in application.yml.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.bus")
public class EventBusRelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventBusRelayApplication.class, args);
    }
}
