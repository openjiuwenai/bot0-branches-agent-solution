/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives the minimal {@code custom_data} payload ({@code {"inputs":{query,intent,...fixed}}})
 * and streams the plan-agent's SSE reply back to the client. The gateway owns the full EDPA
 * envelope: {@link EdpaEnvelopeBuilder} stamps the fixed caller-identity fields (from
 * {@link GatewayProperties}) and the per-request fields from the URL path
 * ({@code conversation_id}, {@code agent_id}), mirrors {@code inputs.query} into {@code input.query},
 * then the selected {@link PlanAgentClient} forwards it to the plan-agent in the configured wire
 * format (A2A {@code /a2a} by default, or REST {@code /v1/query}).
 *
 * <p>The controller is protocol-agnostic: it rebuilds the EDPA envelope and hands the
 * whitelisted headers + URL query params to {@link PlanAgentClient}, which shapes them for its
 * transport. The inbound contract and the SSE reply shape (EDPA {@code {event,data}}) are
 * identical across protocols, so callers and tests need not know which is active.
 *
 * @since 2026-07-08
 */
@RestController
public class GatewayController {
    private static final Logger LOG = LoggerFactory.getLogger(GatewayController.class);

    private final EdpaEnvelopeBuilder envelopeBuilder;
    private final PlanAgentClient client;

    public GatewayController(EdpaEnvelopeBuilder envelopeBuilder, PlanAgentClient client) {
        this.envelopeBuilder = envelopeBuilder;
        this.client = client;
    }

    /**
     * handle
     *
     * @param pathVars    all URI path variables (projectId / agentId / conversationId)
     * @param queryParams inbound URL query params; only {@code type} and {@code workspace_id}
     *                    are mirrored into the forwarded request
     * @param inbound     the minimal {@code custom_data} payload
     * @param response    the servlet response, written as an SSE stream
     * @throws java.io.IOException  if writing the SSE reply to the client fails
     * @throws InterruptedException if the streaming thread is interrupted (propagated to the
     *                              servlet container; not re-asserted via Thread.interrupt)
     */
    @PostMapping(value = "/v1/{projectId}/agents/{agentId}/conversations/{conversationId}")
    public void handle(@PathVariable Map<String, String> pathVars,
                       @RequestParam Map<String, String> queryParams,
                       @RequestBody EdpaInputsRequest inbound,
                       HttpServletResponse response) throws java.io.IOException, InterruptedException {
        String projectId = pathVars.get("projectId");
        String agentId = pathVars.get("agentId");
        String conversationId = pathVars.get("conversationId");
        LOG.info("gateway received request projectId={} agentId={} pathConversationId={} type={} workspaceId={}",
                projectId, agentId, conversationId,
                queryParams.get("type"), queryParams.get("workspace_id"));
        EdpaRequest full = envelopeBuilder.build(inbound.inputs(), conversationId, agentId);
        // metadata.query mirrors the inbound URL query params (stringified to match the EDPA-over-A2A
        // contract — e.g. {"workspace_id":"12","type":"controller"}). REST appends these to the
        // /v1/query URL query string instead.
        Map<String, Object> query = new LinkedHashMap<>();
        if (queryParams.containsKey("type")) {
            query.put("type", queryParams.get("type"));
        }
        if (queryParams.containsKey("workspace_id")) {
            query.put("workspace_id", queryParams.get("workspace_id"));
        }

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);

        // InterruptedException from forward-func is deliberately propagated (not caught and not
        // re-asserted via Thread.interrupt). The SSE response is already committed
        // by this point, so the servlet container just logs and closes the connection.
        client.forward(full, inbound.headers(), query, response.getOutputStream());
    }
}
