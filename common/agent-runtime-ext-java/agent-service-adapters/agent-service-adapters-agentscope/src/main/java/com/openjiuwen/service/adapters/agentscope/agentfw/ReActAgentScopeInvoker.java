/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

final class ReActAgentScopeInvoker implements AgentScopeInvoker {
    private final ReActAgent agent;

    ReActAgentScopeInvoker(ReActAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
    }

    @Override
    public Mono<Msg> call(List<Msg> messages, RuntimeContext context) {
        return agent.call(messages, context);
    }

    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext context) {
        return agent.streamEvents(messages, context);
    }

    @Override
    public AgentState getAgentState(String userId, String sessionId) {
        return agent.getAgentState(userId, sessionId);
    }

    @Override
    public void interrupt(String userId, String sessionId) {
        agent.interrupt(userId, sessionId);
    }
}
