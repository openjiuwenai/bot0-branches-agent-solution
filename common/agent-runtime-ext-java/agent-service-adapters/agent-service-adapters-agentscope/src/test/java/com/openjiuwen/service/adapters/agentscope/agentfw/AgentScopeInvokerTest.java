/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

class AgentScopeInvokerTest {
    @Test
    void reactInvokerDelegatesCallsStateAndInterruptToReactAgent() {
        ReActAgent agent = mock(ReActAgent.class);
        RuntimeContext context = RuntimeContext.builder().userId("user").sessionId("session").build();
        List<Msg> messages = List.of(mock(Msg.class));
        Msg result = mock(Msg.class);
        AgentEvent event = mock(AgentEvent.class);
        AgentState state = mock(AgentState.class);
        when(agent.call(messages, context)).thenReturn(Mono.just(result));
        when(agent.streamEvents(messages, context)).thenReturn(Flux.just(event));
        when(agent.getAgentState("user", "session")).thenReturn(state);
        AgentScopeInvoker invoker = new ReActAgentScopeInvoker(agent);

        assertThat(invoker.call(messages, context).block()).isSameAs(result);
        assertThat(invoker.streamEvents(messages, context).blockFirst()).isSameAs(event);
        assertThat(invoker.getAgentState("user", "session")).isSameAs(state);
        invoker.interrupt("user", "session");

        verify(agent).interrupt("user", "session");
    }

    @Test
    void harnessInvokerUsesHarnessForCallsAndDelegateForStateAndInterrupt() {
        HarnessAgent harness = mock(HarnessAgent.class);
        ReActAgent delegate = mock(ReActAgent.class);
        RuntimeContext context = RuntimeContext.builder().userId("user").sessionId("session").build();
        List<Msg> messages = List.of(mock(Msg.class));
        Msg result = mock(Msg.class);
        AgentEvent event = mock(AgentEvent.class);
        AgentState state = mock(AgentState.class);
        when(harness.getDelegate()).thenReturn(delegate);
        when(harness.call(messages, context)).thenReturn(Mono.just(result));
        when(harness.streamEvents(messages, context)).thenReturn(Flux.just(event));
        when(delegate.getAgentState("user", "session")).thenReturn(state);
        AgentScopeInvoker invoker = new HarnessAgentScopeInvoker(harness);

        assertThat(invoker.call(messages, context).block()).isSameAs(result);
        assertThat(invoker.streamEvents(messages, context).blockFirst()).isSameAs(event);
        assertThat(invoker.getAgentState("user", "session")).isSameAs(state);
        invoker.interrupt("user", "session");

        verify(delegate).interrupt("user", "session");
    }
}
