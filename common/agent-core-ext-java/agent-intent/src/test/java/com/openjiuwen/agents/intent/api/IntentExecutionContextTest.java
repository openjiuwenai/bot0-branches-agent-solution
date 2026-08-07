/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogSnapshot;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests immutable intent execution context snapshots. */
class IntentExecutionContextTest {
    @Test
    void snapshotsConversationWithoutRetainingMutableMessages() {
        List<Object> content = new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("type", "text", "text", "old"))));
        content.add(null);
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("trace", new ArrayList<>(List.of("one"))));
        UserMessage user = UserMessage.builder().content(content).metadata(metadata).build();
        ToolCall call = ToolCall.builder().id("call-1").name("intent_match").arguments("{\"semantic\":\"transfer\"}")
                .build();
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        ModelContext modelContext = mock(ModelContext.class);
        when(modelContext.getMessages()).thenReturn(List.of(user, assistant));

        IntentExecutionContext context = IntentExecutionContext.create(IntentSuiteConfig.defaults(),
                new IntentCatalogSnapshot(3L, new InitializedIntents(List.of(), null)), Map.of("semantic", "transfer"),
                Map.of("context", modelContext, "live", new Object()));

        mutableMap(content.get(0)).put("text", "changed");
        mutableList(metadata.get("trace")).add("two");
        call.setName("changed_tool");

        assertThat(context.routingSemantic()).isEqualTo("transfer");
        Object latestInput = context.latestUserInput().orElseThrow();
        assertThat(latestInput).isEqualTo(context.conversation().get(0).contentSnapshot());
        if (!(latestInput instanceof List<?> latestParts)) {
            throw new AssertionError("expected latest user input parts");
        }
        assertThat(latestParts).hasSize(2);
        assertThat(mutableMap(latestParts.get(0))).containsEntry("text", "old");
        assertThat(context.conversation().get(0).metadata().get("trace")).isEqualTo(List.of("one"));
        assertThat(context.conversation().get(1).toolCalls().get(0).name()).isEqualTo("intent_match");
        assertThat(context.toolKwargs()).containsKeys("context", "live");
        assertThatThrownBy(() -> context.toolInputs().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void usesTheLastUserMessageAndRequiresNonBlankSemantic() {
        ModelContext modelContext = mock(ModelContext.class);
        List<BaseMessage> messages = List.of(new UserMessage("first"), new AssistantMessage("answer"),
                new UserMessage("latest"));
        when(modelContext.getMessages()).thenReturn(messages);
        IntentCatalogSnapshot snapshot = new IntentCatalogSnapshot(0L, new InitializedIntents(List.of(), null));

        IntentExecutionContext context = IntentExecutionContext.create(IntentSuiteConfig.defaults(), snapshot,
                Map.of("semantic", "latest request"), Map.of("context", modelContext));
        assertThat(context.latestUserInput()).contains("latest");
        assertThatThrownBy(() -> IntentExecutionContext.create(IntentSuiteConfig.defaults(), snapshot,
                Map.of("semantic", " "), Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mutableList(Object value) {
        return (List<String>) value;
    }
}
