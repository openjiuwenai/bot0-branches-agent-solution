/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tests user-visible progress generated from the actual DeepAgent todo plan. */
class BankPlanProgressRailTest {
    @Test
    void emitsCreatedPlanBeforeFirstRemoteStepWithoutChangingDelegateArguments() {
        TestInvocation invocation = invokeRail(List.of(
                todo("first", "给张三转账100元", TodoStatus.IN_PROGRESS, null),
                todo("second", "给李四转账100元", TodoStatus.PENDING, null)));

        assertThat(invocation.inputs().getToolCall().getArguments())
                .isEqualTo("{\"agentName\":\"transfer-agent\",\"remoteInput\":\"transfer\"}");
        assertProgress(invocation.session(), 1, """
                执行计划：
                1. 给张三转账100元
                2. 给李四转账100元

                当前执行第 1/2 步：给张三转账100元。""", "给张三转账100元");
    }

    @Test
    void emitsCompletedResultBeforeNextRemoteStep() {
        TestInvocation invocation = invokeRail(List.of(
                todo("first", "给张三转账100元", TodoStatus.COMPLETED, "已向张三转账100元"),
                todo("second", "给李四转账100元", TodoStatus.IN_PROGRESS, null)));

        assertProgress(invocation.session(), 2, """
                第 1/2 步已完成：已向张三转账100元。

                当前执行第 2/2 步：给李四转账100元。""", "给李四转账100元");
    }

    private static TestInvocation invokeRail(List<TodoItem> todos) {
        String json = "{\"agentName\":\"transfer-agent\",\"remoteInput\":\"transfer\"}";
        ToolCall toolCall = ToolCall.builder().id("call-1").name("a2a_delegate").arguments(json).build();
        ToolCallInputs inputs = ToolCallInputs.builder().toolCall(toolCall).toolName("a2a_delegate")
                .toolArgs(json).build();
        TestSession session = new TestSession("session-1");
        TaskPlanningRail taskPlanningRail = new TaskPlanningRail() {
            @Override
            public List<TodoItem> cachedTodos(String sessionId) {
                return todos;
            }
        };
        new BankPlanProgressRail(taskPlanningRail).beforeToolCall(
                AgentCallbackContext.builder().inputs(inputs).session(session).build());
        return new TestInvocation(inputs, session);
    }

    private static void assertProgress(TestSession session, int currentStep, String message, String currentContent) {
        assertThat(session.outputs).singleElement().isInstanceOfSatisfying(OutputSchema.class, output -> {
            assertThat(output.getType()).isEqualTo("bank_plan_progress");
            assertThat(output.getPayload()).isInstanceOf(Map.class);
            Map<?, ?> payload = (Map<?, ?>) output.getPayload();
            assertThat(payload.get("message")).isEqualTo(message);
            assertThat(payload.get("currentStep")).isEqualTo(currentStep);
            assertThat(payload.get("totalSteps")).isEqualTo(2);
            assertThat(payload.get("tasks")).isInstanceOf(List.class);
            List<?> tasks = (List<?>) payload.get("tasks");
            assertThat(tasks).anySatisfy(task -> {
                assertThat(task).isInstanceOf(Map.class);
                assertThat(((Map<?, ?>) task).get("content")).isEqualTo(currentContent);
            });
        });
    }

    private static TodoItem todo(String id, String content, TodoStatus status, String resultSummary) {
        return TodoItem.builder().id(id).content(content).activeForm(content).description(content).status(status)
                .resultSummary(resultSummary).build();
    }

    private static final class TestSession implements Session {
        private final String sessionId;
        private final Map<String, Object> state = new HashMap<>();
        private final List<Object> outputs = new ArrayList<>();

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

        @Override
        public void writeStream(Object data) {
            outputs.add(data);
        }
    }

    private record TestInvocation(ToolCallInputs inputs, TestSession session) {
    }
}
