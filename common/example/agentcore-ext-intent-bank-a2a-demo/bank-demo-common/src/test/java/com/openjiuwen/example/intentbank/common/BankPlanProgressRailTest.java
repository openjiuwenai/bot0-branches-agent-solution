/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.tools.FileTodoStorage;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tests user-visible progress generated from the actual DeepAgent todo plan. */
class BankPlanProgressRailTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void addsCreatedPlanBeforeFirstRemoteStep() throws Exception {
        save(List.of(todo("first", "给张三转账100元", TodoStatus.IN_PROGRESS, null),
                todo("second", "给李四转账100元", TodoStatus.PENDING, null)));

        ToolCallInputs inputs = invokeRail();

        assertThat(arguments(inputs).get("parentProgress")).isEqualTo("""
                执行计划：
                1. 给张三转账100元
                2. 给李四转账100元

                当前执行第 1/2 步：给张三转账100元。""");
    }

    @Test
    void addsCompletedResultBeforeNextRemoteStep() throws Exception {
        save(List.of(todo("first", "给张三转账100元", TodoStatus.COMPLETED, "已向张三转账100元"),
                todo("second", "给李四转账100元", TodoStatus.IN_PROGRESS, null)));

        ToolCallInputs inputs = invokeRail();

        assertThat(arguments(inputs).get("parentProgress")).isEqualTo("""
                第 1/2 步已完成：已向张三转账100元。

                当前执行第 2/2 步：给李四转账100元。""");
    }

    private void save(List<TodoItem> todos) throws Exception {
        new FileTodoStorage(workspace.resolve(".todo")).save("session-1", todos);
    }

    private ToolCallInputs invokeRail() {
        String json = "{\"agentName\":\"transfer-agent\",\"remoteInput\":\"transfer\"}";
        ToolCall toolCall = ToolCall.builder().id("call-1").name("a2a_delegate").arguments(json).build();
        ToolCallInputs inputs = ToolCallInputs.builder().toolCall(toolCall).toolName("a2a_delegate")
                .toolArgs(json).build();
        Session session = new TestSession("session-1");
        new BankPlanProgressRail(workspace).beforeToolCall(
                AgentCallbackContext.builder().inputs(inputs).session(session).build());
        return inputs;
    }

    private static Map<String, Object> arguments(ToolCallInputs inputs) throws Exception {
        return OBJECT_MAPPER.readValue(inputs.getToolCall().getArguments(), new TypeReference<>() {
        });
    }

    private static TodoItem todo(String id, String content, TodoStatus status, String resultSummary) {
        return TodoItem.builder().id(id).content(content).activeForm(content).description(content).status(status)
                .resultSummary(resultSummary).build();
    }

    private static final class TestSession implements Session {
        private final String sessionId;
        private final Map<String, Object> state = new HashMap<>();

        private TestSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public Object getState(String key) {
            return state.get(key);
        }

        @Override
        public void updateState(Map<String, Object> values) {
            state.putAll(values);
        }
    }
}
