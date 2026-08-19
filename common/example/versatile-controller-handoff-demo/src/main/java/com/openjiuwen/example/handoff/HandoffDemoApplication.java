/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.handoff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deployment example for the FEAT-002 Versatile controller intent-handoff adapter
 * (agent-service-adapters-versatile-controller-handoff).
 *
 * <p>One jar, two layers selected by profile ({@code layer1} / {@code layer2}), plus
 * the {@code mock-controller} profile which hosts an in-process mock Versatile
 * controller emitting the real {@code node_finished} wire format. Layer-to-layer
 * handoff calls use the runtime's default {@code RemoteAgentCaller}
 * ({@code A2ARemoteAgentClient}) with static discovery: each layer configures
 * {@code openjiuwen.service.a2a.remote-agents} pointing at the peer runtime, whose
 * well-known agent card is fetched at startup. Loop-trace metadata rides the A2A
 * {@code MessageSendParams.metadata} as the L2 spec §7.2 循环保护（跨请求）journey
 * expects. See scripts/local-e2e.sh.
 *
 * @since 0.1.0
 */
@SpringBootApplication
public class HandoffDemoApplication {

    /**
     * Entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(HandoffDemoApplication.class, args);
    }
}
