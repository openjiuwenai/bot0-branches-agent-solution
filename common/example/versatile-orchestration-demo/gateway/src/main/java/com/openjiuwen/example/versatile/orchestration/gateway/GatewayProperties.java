/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Gateway configuration:
 * <ul>
 *   <li>{@code planAgentBaseUrl} — the plan-agent's base URL (the gateway appends the protocol
 *       path: {@code /a2a} under {@link Protocol#A2A}, {@code /v1/query} under
 *       {@link Protocol#REST});</li>
 *   <li>{@code planAgentProtocol} — which downstream wire format the gateway speaks to the
 *       plan-agent. {@code A2A} (default) builds a JSON-RPC {@code SendStreamingMessage} and
 *       manages INPUT_REQUIRED task-resume state via {@link ResumeStateStore}; {@code REST}
 *       builds a {@code /v1/query} body and relies on the runtime's conversation-id shadow-task
 *       routing (no taskId resume cache). Bound by Spring from {@code plan-agent-protocol}
 *       (case-insensitive); a missing value defaults to {@code A2A} (both here and via the
 *       A2A client's {@code @ConditionalOnProperty(matchIfMissing=true)});</li>
 *   <li>{@code forwardHeaderWhitelist} — which inbound {@code headers} entries are forwarded on the
 *       outbound request (A2A: into {@code metadata.headers}; REST: as HTTP headers);</li>
 *   <li>{@code roleName} / {@code roleId} / {@code timeout} — the fixed EDPA envelope fields the
 *       gateway stamps onto every request when rebuilding the full body from the minimal
 *       {@code custom_data} payload (the inbound carries only {@code custom_data.inputs});</li>
 *   <li>{@code userId} / {@code spaceId} — REST-only caller-identity fields carried at the top
 *       level of the {@code /v1/query} body (unused under A2A).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "versatile-orchestration.gateway")
public record GatewayProperties(
        String planAgentBaseUrl,
        Protocol planAgentProtocol,
        Set<String> forwardHeaderWhitelist,
        String roleName,
        String roleId,
        String timeout,
        String userId,
        String spaceId) {

    /**
     * Default a missing {@code planAgentProtocol} to {@link Protocol#A2A} so callers (and tests)
     * that construct the record without specifying a protocol get the legacy A2A behaviour. The
     * active client bean is still selected by {@code @ConditionalOnProperty} on the raw property
     * source; this default only governs direct field reads.
     */
    public GatewayProperties {
        if (planAgentProtocol == null) {
            planAgentProtocol = Protocol.A2A;
        }
    }

    /**
     * Downstream wire format the gateway uses to talk to the plan-agent. Bound by Spring from
     * {@code plan-agent-protocol} (relaxed matching: {@code a2a}/{@code rest} any case).
     */
    public enum Protocol {
        /** JSON-RPC {@code SendStreamingMessage} to {@code <base>/a2a} with INPUT_REQUIRED taskId resume. */
        A2A,
        /** REST {@code POST <base>/v1/query} with a conversation-id-routed shadow task (no taskId). */
        REST
    }
}
