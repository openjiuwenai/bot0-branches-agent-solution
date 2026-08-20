/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbound contextId rewrite policy: prefix the resolved target agentId
 * ({@code <agentId>-<原contextId>}) so the downstream runtime sees a
 * different contextId per target, deterministically (INPUT_REQUIRED 续调
 * rewrite must stay stable).
 *
 * @since 0.1.0
 */
class ForwardContextIdRemoteAgentCallerTest {

    private static final RemoteCallOutcome DONE = new RemoteCallOutcome("task-9", null, "final", "ok", null);

    private final CapturingDelegate delegate = new CapturingDelegate();

    @Test
    void rewritesContextIdWithTargetAgentPrefix() {
        ForwardContextIdRemoteAgentCaller caller = new ForwardContextIdRemoteAgentCaller(delegate);
        caller.callOutcome(call("agent_card_l2", "conv-123"), null);
        assertThat(delegate.captured.get().contextId()).isEqualTo("agent_card_l2-conv-123");
        assertThat(delegate.captured.get().agentName()).isEqualTo("agent_card_l2");
        assertThat(delegate.captured.get().message()).isEqualTo("转调机票");
        assertThat(delegate.captured.get().taskId()).isEqualTo("task-1");
        assertThat(delegate.captured.get().metadata()).containsEntry("tenantId", "t1");
    }

    /** 同一转调链的续调用相同原 contextId → 改写结果一致（确定性，续接不断）。 */
    @Test
    void rewriteIsDeterministicAcrossResume() {
        ForwardContextIdRemoteAgentCaller caller = new ForwardContextIdRemoteAgentCaller(delegate);
        caller.callOutcome(call("agent_card_l2", "conv-123"), null);
        caller.callOutcome(call("agent_card_l2", "conv-123"), null);
        assertThat(delegate.captured.get().contextId()).isEqualTo("agent_card_l2-conv-123");
    }

    /** 已带同目标前缀的 contextId 不二次包装（幂等防御）。 */
    @Test
    void rewriteIsIdempotentForSameTarget() {
        ForwardContextIdRemoteAgentCaller caller = new ForwardContextIdRemoteAgentCaller(delegate);
        caller.callOutcome(call("agent_card_l2", "agent_card_l2-conv-123"), null);
        assertThat(delegate.captured.get().contextId()).isEqualTo("agent_card_l2-conv-123");
    }

    /** 空白 contextId 原样透传（无值可派生）。 */
    @Test
    void blankContextIdPassesThrough() {
        ForwardContextIdRemoteAgentCaller caller = new ForwardContextIdRemoteAgentCaller(delegate);
        caller.callOutcome(call("agent_card_l2", ""), null);
        assertThat(delegate.captured.get().contextId()).isEmpty();
    }

    private static RemoteCall call(String agentName, String contextId) {
        return new RemoteCall(agentName, "转调机票", contextId, "task-1",
                Map.of("tenantId", "t1"), Map.of(), false);
    }

    private static final class CapturingDelegate implements RemoteAgentCaller {
        private final AtomicReference<RemoteCall> captured = new AtomicReference<>();

        @Override
        public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
                RemoteAgentCaller.EventObserver eventObserver) {
            captured.set(call);
            return CompletableFuture.completedFuture(DONE);
        }
    }
}
