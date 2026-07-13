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

interface AgentScopeInvoker {
    Mono<Msg> call(List<Msg> messages, RuntimeContext context);

    Flux<AgentEvent> streamEvents(List<Msg> messages, RuntimeContext context);

    AgentState getAgentState(String userId, String sessionId);

    void interrupt(String userId, String sessionId);
}
