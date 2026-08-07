/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

class IntentRoutingRailTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void writesReturnActionAsIntentResultAndSkipsExecution() throws Exception {
        IntentSuite suite = suite(context -> new ReturnAction(Map.of("answer", 42)));
        IntentRoutingRail rail = new IntentRoutingRail(suite);
        ToolCall toolCall = intentCall("call-1", "calculate 6 times 7");
        ToolCallInputs inputs = inputs(toolCall);
        AgentCallbackContext callback = AgentCallbackContext.builder().inputs(inputs).extra(new LinkedHashMap<>())
                .build();

        rail.beforeToolCall(callback);

        assertThat(callback.getExtra()).containsEntry("_skip_tool", true);
        assertThat(toolCall.getName()).isEqualTo(IntentRoutingRail.TOOL_NAME);
        assertThat(inputs.getToolMsg().getToolCallId()).isEqualTo("call-1");
        Map<String, Object> payload = OBJECT_MAPPER.readValue(inputs.getToolMsg().getContentAsString(),
                new TypeReference<>() {
                });
        assertThat(payload).containsEntry("status", "MATCHED").containsEntry("intentId", "intent");
        assertThat(stringObjectMap(payload.get("result"))).containsEntry("answer", 42);
    }

    @Test
    void rewritesBothToolViewsForRegisteredLocalToolAndKeepsCallId() {
        IntentSuite suite = suite(context -> new InvokeToolAction("calculator", Map.of("expression", "6*7")));
        IntentRoutingRail rail = new IntentRoutingRail(suite);
        ReActAgent agent = reactAgent();
        agent.getAbilityManager().add(ToolCard.builder().id("calculator").name("calculator").description("calculate")
                .inputParams(Map.of()).build());
        ToolCall toolCall = intentCall("call-2", "calculate 6 times 7");
        ToolCallInputs inputs = inputs(toolCall);
        AgentCallbackContext callback = AgentCallbackContext.builder().agent(agent).inputs(inputs)
                .extra(new LinkedHashMap<>()).build();

        rail.beforeToolCall(callback);

        assertThat(callback.getExtra()).doesNotContainKey("_skip_tool");
        assertThat(inputs.getToolName()).isEqualTo("calculator");
        assertThat(inputs.getToolArgs()).isEqualTo(Map.of("expression", "6*7"));
        assertThat(toolCall.getName()).isEqualTo("calculator");
        assertThat(toolCall.getId()).isEqualTo("call-2");
        assertThat(toolCall.getArguments()).isEqualTo("{\"expression\":\"6*7\"}");
    }

    @Test
    void allowsReservedA2aTargetButRejectsUnknownAndRecursiveTargets() throws Exception {
        IntentRoutingRail a2aRail = new IntentRoutingRail(
                suite(context -> new InvokeToolAction("a2a_delegate", Map.of("agentName", "transfer"))));
        ToolCall a2aCall = intentCall("call-3", "transfer");
        ToolCallInputs a2aInputs = inputs(a2aCall);
        a2aRail.beforeToolCall(AgentCallbackContext.builder().inputs(a2aInputs).extra(new LinkedHashMap<>()).build());
        assertThat(a2aCall.getName()).isEqualTo("a2a_delegate");

        IntentRoutingRail unknownRail = new IntentRoutingRail(
                suite(context -> new InvokeToolAction("missing", Map.of())));
        ToolCallInputs unknownInputs = inputs(intentCall("call-4", "missing"));
        AgentCallbackContext unknownCallback = AgentCallbackContext.builder().agent(reactAgent()).inputs(unknownInputs)
                .extra(new LinkedHashMap<>()).build();
        unknownRail.beforeToolCall(unknownCallback);
        assertThat(unknownCallback.getExtra()).containsEntry("_skip_tool", true);
        assertThat(json(unknownInputs)).containsEntry("status", "FAILED").containsEntry("message", "目标工具未注册: missing");

        IntentRoutingRail recursiveRail = new IntentRoutingRail(
                suite(context -> new InvokeToolAction(IntentRoutingRail.TOOL_NAME, Map.of())));
        ToolCallInputs recursiveInputs = inputs(intentCall("call-5", "again"));
        AgentCallbackContext recursiveCallback = AgentCallbackContext.builder().inputs(recursiveInputs)
                .extra(new LinkedHashMap<>()).build();
        recursiveRail.beforeToolCall(recursiveCallback);
        assertThat(json(recursiveInputs)).containsEntry("status", "FAILED");
    }

    @Test
    void higherPriorityRoutingRunsBeforeDownstreamToolRail() {
        IntentSuite suite = suite(context -> new InvokeToolAction("a2a_delegate", Map.of("agentName", "transfer")));
        ReActAgent agent = reactAgent();
        AtomicReference<String> downstreamObserved = new AtomicReference<>();
        AgentRail downstream = new AgentRail() {
            @Override
            public void beforeToolCall(AgentCallbackContext context) {
                downstreamObserved.set(((ToolCallInputs) context.getInputs()).getToolName());
            }
        };
        agent.registerRail(downstream);
        agent.registerRail(new IntentRoutingRail(suite));
        ToolCallInputs inputs = inputs(intentCall("call-6", "transfer"));
        AgentCallbackContext callback = AgentCallbackContext.builder().agent(agent).inputs(inputs)
                .extra(new LinkedHashMap<>()).build();

        agent.fireCallbackEvent(AgentCallbackEvent.BEFORE_TOOL_CALL, callback);

        assertThat(downstreamObserved.get()).isEqualTo("a2a_delegate");
    }

    private static IntentSuite suite(com.openjiuwen.agents.intent.api.IntentResultFunction resultFunction) {
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(context -> Optional
                        .of(context.catalogSnapshot().initializedIntents().matchableIntents().get(0)))
                .build();
        suite.replaceCatalog(new IntentCatalogInput(List.of(),
                List.of(new CustomIntentRegistration("intent", "intent description", resultFunction)), null));
        return suite;
    }

    private static ToolCall intentCall(String id, String semantic) {
        return ToolCall.builder().id(id).name(IntentRoutingRail.TOOL_NAME)
                .arguments("{\"semantic\":\"" + semantic + "\"}").build();
    }

    private static ToolCallInputs inputs(ToolCall toolCall) {
        return ToolCallInputs.builder().toolCall(toolCall).toolName(toolCall.getName())
                .toolArgs(toolCall.getArguments()).build();
    }

    private static ReActAgent reactAgent() {
        return new ReActAgent(AgentCard.builder().id("agent").name("agent").description("agent").build());
    }

    private static Map<String, Object> json(ToolCallInputs inputs) throws Exception {
        return OBJECT_MAPPER.readValue(inputs.getToolMsg().getContentAsString(), new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
