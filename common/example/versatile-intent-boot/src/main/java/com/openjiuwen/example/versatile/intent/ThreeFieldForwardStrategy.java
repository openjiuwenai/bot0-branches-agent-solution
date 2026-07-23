/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentAnswerExtractor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.orchestrator.ServeForwardStrategy;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Map;
import java.util.Optional;

/**
 * {@link ServeForwardStrategy} that detects a Versatile intent-workflow
 * three-field answer envelope and builds a {@link RemoteAgentCall} to forward
 * to the next layer.
 *
 * <p>A three-field envelope is a chunk or response result shaped as
 * {@code {"type":"answer", "agent_id":"...", "response_content":"...", "intent_id":"..."}}.
 * When {@code agent_id} is present and non-blank, this strategy returns a
 * {@link RemoteAgentCall} targeting that agent; the orchestrator then executes
 * the call, captures the remote result, and (if the remote also returns a
 * three-field envelope) forwards again — forming the layer-1 → layer-2 →
 * downstream chain.
 *
 * <p>Self-forward (envelope {@code agent_id} equal to the orchestrator's own
 * agent id) is NOT skipped. Re-classification per PRD §4.6 requires the
 * downstream business runtime to forward back to the fixed layer-1 agent,
 * creating a new Task — even when that target equals the current runtime's
 * own agent identity. Loop protection (deadline, max-jump count, repeated-path
 * detection) is the responsibility of the runtime downstream-call capability
 * per L2 §2.2, not this strategy.
 *
 * @since 0.1.0
 */
public class ThreeFieldForwardStrategy implements ServeForwardStrategy {

    @Override
    public Optional<RemoteAgentCall> evaluateForward(QueryResponse localResponse, ServeRequest request) {
        if (!(localResponse.getResult() instanceof Map<?, ?> envelope)) {
            return Optional.empty();
        }
        return buildForwardCall(envelope, request, false);
    }

    @Override
    public Optional<RemoteAgentCall> interceptStreamEnvelope(QueryChunk chunk, ServeRequest request) {
        if (!QueryChunk.TYPE_CHUNK.equals(chunk.getType())) {
            return Optional.empty();
        }
        if (!(chunk.getData() instanceof Map<?, ?> envelope)) {
            return Optional.empty();
        }
        if (!RemoteAgentAnswerExtractor.ANSWER_ENVELOPE_TYPE.equals(envelope.get("type"))) {
            return Optional.empty();
        }
        return buildForwardCall(envelope, request, true);
    }

    private Optional<RemoteAgentCall> buildForwardCall(Map<?, ?> envelope, ServeRequest request, boolean streaming) {
        Object agentIdObj = envelope.get("agent_id");
        if (!(agentIdObj instanceof String aid) || aid.isBlank()) {
            return Optional.empty();
        }
        Object rc = envelope.get("response_content");
        String rcStr = rc instanceof String s ? s : null;
        return Optional.of(new RemoteAgentCall(aid, request, rcStr, request.getConversationId(),
                null, request.lastUserQuery(), streaming));
    }
}
