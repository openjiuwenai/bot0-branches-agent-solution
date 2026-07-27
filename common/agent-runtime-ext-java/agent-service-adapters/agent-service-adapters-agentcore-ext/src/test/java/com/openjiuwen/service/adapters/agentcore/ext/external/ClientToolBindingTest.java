/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests request-scoped client tool result binding.
 *
 * @since 2026-07-25
 */
class ClientToolBindingTest {
    @Test
    void convertsCompleteMultipleTargetedResultsForCoreHandler() {
        ServeRequest request = request(Map.of(
                "_interrupt", multiplePending(),
                "runtime.remoteToolInputs", Map.of("call-a", "page body", "call-b", "confirmed")));

        try (ClientToolRail.Binding ignored = ClientToolRail.bind(agent(), request)) {
            assertThat(request.getMetadata()).doesNotContainKey("runtime.remoteToolInputs");
            assertThat(request.getMetadata().get("runtime.remoteToolResults")).isEqualTo(
                    Map.of("call-a", "page body", "call-b", "confirmed"));
        }
    }

    @Test
    void convertsExplicitlyTargetedSingleResult() {
        ServeRequest request = request(Map.of(
                "_interrupt", singlePending("call-a", "readCurrentPage"),
                "runtime.remoteToolInputs", Map.of("call-a", "page body")));

        try (ClientToolRail.Binding ignored = ClientToolRail.bind(agent(), request)) {
            assertThat(request.getMetadata().get("runtime.remoteToolResults"))
                    .isEqualTo(Map.of("call-a", "page body"));
        }
    }

    @Test
    void leavesUntargetedSingleResultForCoreStringResume() {
        ServeRequest request = request(Map.of("_interrupt", singlePending("call-a", "readCurrentPage")));

        try (ClientToolRail.Binding ignored = ClientToolRail.bind(agent(), request)) {
            assertThat(request.getMetadata()).doesNotContainKeys(
                    "runtime.remoteToolInputs", "runtime.remoteToolResults");
        }
    }

    @Test
    void rejectsIncompleteOrUnknownMultipleResultTargets() {
        ServeRequest incomplete = request(Map.of(
                "_interrupt", multiplePending(),
                "runtime.remoteToolInputs", Map.of("call-a", "page body")));
        ServeRequest unknown = request(Map.of(
                "_interrupt", multiplePending(),
                "runtime.remoteToolInputs", Map.of("call-a", "page body", "call-x", "unknown")));

        assertThatThrownBy(() -> ClientToolRail.bind(agent(), incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result targets");
        assertThatThrownBy(() -> ClientToolRail.bind(agent(), unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result targets");
    }

    @Test
    void rejectsDuplicateVisibleToolNamesAndServerToolConflicts() {
        ServeRequest duplicate = request(Map.of("clientTools", List.of(
                clientTool("readCurrentPage"), clientTool("readCurrentPage"))));
        ReActAgent conflictingAgent = agent();
        conflictingAgent.getAbilityManager().add(ToolCard.builder()
                .id("server-read").name("readCurrentPage").description("server tool")
                .inputParams(Map.of("type", "object")).build());
        ServeRequest conflict = request(Map.of("clientTools", List.of(clientTool("readCurrentPage"))));

        assertThatThrownBy(() -> ClientToolRail.bind(agent(), duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate client tool name");
        assertThatThrownBy(() -> ClientToolRail.bind(conflictingAgent, conflict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts");
    }

    @Test
    void noClientToolContextReturnsNoopBinding() {
        ServeRequest request = request(Map.of());
        ReActAgent agent = agent();

        try (ClientToolRail.Binding ignored = ClientToolRail.bind(agent, request)) {
            assertThat(agent.getAgentCallbackManager().hasHooks(AgentCallbackEvent.BEFORE_MODEL_CALL)).isFalse();
            assertThat(agent.getAgentCallbackManager().hasHooks(AgentCallbackEvent.BEFORE_TOOL_CALL)).isFalse();
        }
    }

    private static ServeRequest request(Map<String, Object> metadata) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx");
        request.setMetadata(new LinkedHashMap<>(metadata));
        return request;
    }

    private static ReActAgent agent() {
        return new ReActAgent(AgentCard.builder().id("agent").name("Agent").description("Agent").build());
    }

    private static Map<String, Object> clientTool(String name) {
        return Map.of(
                "name", name,
                "description", "Read current page",
                "inputSchema", Map.of("type", "object", "properties", Map.of()));
    }

    private static Map<String, Object> singlePending(String id, String name) {
        return Map.of(
                "type", "__interaction__",
                "toolCallId", id,
                "toolName", name,
                "context", Map.of("_interrupt_kind", "client_tool"));
    }

    private static Map<String, Object> multiplePending() {
        return Map.of("items", List.of(
                singlePending("call-a", "readCurrentPage"),
                singlePending("call-b", "confirmLocalAction")));
    }
}
