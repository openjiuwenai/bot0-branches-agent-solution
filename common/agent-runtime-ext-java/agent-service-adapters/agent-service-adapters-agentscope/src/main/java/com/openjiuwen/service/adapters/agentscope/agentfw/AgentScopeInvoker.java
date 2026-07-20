/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Internal invocation contract shared by ReAct and Harness agent wrappers.
 *
 * @since 2026-07-20
 */
interface AgentScopeInvoker {
    /**
     * Calls an AgentScope agent and returns its final message.
     *
     * @param messages input messages
     * @param context runtime context
     * @return final message publisher
     */
    Mono<Msg> call(List<Msg> messages, RuntimeContext context);

    /**
     * Streams AgentScope execution events.
     *
     * @param messages input messages
     * @param context runtime context
     * @return event publisher
     */
    Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext context);

    /**
     * Returns state for an AgentScope session.
     *
     * @param userId user identifier
     * @param sessionId session identifier
     * @return current agent state
     */
    AgentState getAgentState(String userId, String sessionId);

    /**
     * Interrupts the active AgentScope session.
     *
     * @param userId user identifier
     * @param sessionId session identifier
     */
    void interrupt(String userId, String sessionId);
}
