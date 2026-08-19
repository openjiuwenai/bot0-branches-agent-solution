/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamEventMapperTest {

    private final DownstreamEventMapper mapper = new DownstreamEventMapper();

    @Test
    void completedMapsToCompleteAction() {
        DownstreamEventMapper.MappedTerminal t = mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "answer", null, null),
                "agent_card_hotel");
        assertThat(t.action()).isEqualTo(DownstreamEventMapper.TerminalAction.COMPLETE);
    }

    @Test
    void inputRequiredMapsToGatedAction() {
        DownstreamEventMapper.MappedTerminal t = mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_INPUT_REQUIRED, "INPUT_REQUIRED",
                        null, "please choose", null),
                "agent_card_hotel");
        assertThat(t.action()).isEqualTo(DownstreamEventMapper.TerminalAction.INPUT_REQUIRED);
    }

    @Test
    void businessFailureMapsToErrorWithDiagnostics() {
        DownstreamEventMapper.MappedTerminal t = mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_FAILED, "REMOTE_BUSINESS_FAILURE",
                        "booking sold out", null, new AgentFailureDescriptor("HOTEL_SOLD_OUT", 4201, false)),
                "agent_card_hotel");
        assertThat(t.action()).isEqualTo(DownstreamEventMapper.TerminalAction.ERROR);
        assertThat(t.errorCode()).isEqualTo("VERSATILE_HANDOFF_REMOTE_BUSINESS_FAILURE");
        assertThat(t.detail()).contains("booking sold out").contains("HOTEL_SOLD_OUT").contains("agent_card_hotel");
    }

    @Test
    void rejectedMapsToRejectedErrorCode() {
        DownstreamEventMapper.MappedTerminal t = mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_REJECTED, "REMOTE_REJECTED", "no", null, null),
                "agent_card_hotel");
        assertThat(t.action()).isEqualTo(DownstreamEventMapper.TerminalAction.ERROR);
        assertThat(t.errorCode()).isEqualTo("VERSATILE_HANDOFF_REMOTE_REJECTED");
    }

    @Test
    void nullOrUnknownCategoryIsResultInvalid() {
        assertThat(mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_COMPLETED, null, null, null, null), "a")
                .errorCode()).isEqualTo("VERSATILE_HANDOFF_RESULT_INVALID");
        assertThat(mapper.fromOutcome(
                new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_COMPLETED, "SOMETHING_ELSE", null, null, null), "a")
                .errorCode()).isEqualTo("VERSATILE_HANDOFF_RESULT_INVALID");
    }
}
