/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeBusDemoAgentHandlerTest {
    @Test
    void callerDelegatesThenReturnsRemoteResult() {
        RuntimeBusDemoAgentHandler handler = RuntimeBusDemoAgentHandler.caller();
        ServeRequest initial = request("hello");

        QueryResponse delegated = handler.query(initial);

        assertThat(delegated.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");

        ServeRequest resumed = request("hello");
        resumed.setMetadata(Map.of("runtime.remoteToolResults",
                Map.of(RuntimeBusDemoAgentHandler.TOOL_CALL_ID, "target result")));

        assertThat(handler.query(resumed).getResult().toString())
                .contains("source runtime received remote result: target result");
    }

    @Test
    void calleeReturnsDeterministicMarker() {
        QueryResponse response = RuntimeBusDemoAgentHandler.callee().query(request("hello"));

        assertThat(response.getResult().toString()).contains("target runtime received: hello");
    }

    @Test
    void calleeRequestsInputForApprovalScenario() {
        QueryResponse response = RuntimeBusDemoAgentHandler.callee()
                .query(request(RuntimeBusDemoAgentHandler.INPUT_REQUIRED_TRIGGER));

        assertThat(response.getResult()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("_interrupt");
        assertThat(response.getResult().toString()).contains("Approve target runtime operation?");
    }

    private static ServeRequest request(String text) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("runtime-bus-runtime-context");
        request.setMessages(java.util.List.of(Map.of("role", "user", "content", text)));
        request.setMetadata(Map.of());
        return request;
    }
}
