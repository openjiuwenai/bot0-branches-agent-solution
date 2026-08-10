/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Emits the Intent Agent's actual task-plan progress before a remote invocation. */
public final class BankPlanProgressRail extends AgentRail {
    /** Runs after intent routing and before the A2A delegate interrupt Rail. */
    public static final int PRIORITY = 100;

    private static final String A2A_DELEGATE = "a2a_delegate";
    private static final Logger log = LoggerFactory.getLogger(BankPlanProgressRail.class);

    private final TaskPlanningRail taskPlanningRail;

    /**
     * Creates a progress Rail for the DeepAgent workspace.
     *
     * @param taskPlanningRail Intent Agent task-planning Rail
     */
    public BankPlanProgressRail(TaskPlanningRail taskPlanningRail) {
        this.taskPlanningRail = Objects.requireNonNull(taskPlanningRail, "taskPlanningRail");
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void beforeToolCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ToolCallInputs inputs)
                || !A2A_DELEGATE.equals(inputs.getToolName()) || context.getSession() == null) {
            return;
        }
        String sessionId = context.getSession().getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<TodoItem> todos = taskPlanningRail.cachedTodos(sessionId);
        if (todos.size() < 2) {
            return;
        }
        String progress = progressMessage(todos);
        if (progress.isBlank()) {
            return;
        }
        int currentIndex = currentIndex(todos);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", progress);
        payload.put("currentStep", currentIndex + 1);
        payload.put("totalSteps", todos.size());
        payload.put("tasks", todos.stream().map(BankPlanProgressRail::taskPayload).toList());
        context.getSession().writeStream(new OutputSchema("bank_plan_progress", 0, payload));
        log.info("BANK_DEMO_PLAN_PROGRESS sessionId={} taskCount={} currentStep={}", sessionId, todos.size(),
                currentIndex + 1);
    }

    private static Map<String, Object> taskPayload(TodoItem todo) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", todo == null ? "" : text(todo.getId()));
        task.put("content", content(todo));
        task.put("status", todo == null || todo.getStatus() == null ? "" : todo.getStatus().name());
        task.put("resultSummary", todo == null ? "" : text(todo.getResultSummary()));
        return task;
    }

    private static String progressMessage(List<TodoItem> todos) {
        int currentIndex = currentIndex(todos);
        if (currentIndex < 0) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        boolean hasCompleted = todos.stream().anyMatch(BankPlanProgressRail::isCompleted);
        if (!hasCompleted) {
            message.append("执行计划：\n");
            for (int index = 0; index < todos.size(); index++) {
                message.append(index + 1).append(". ").append(content(todos.get(index))).append('\n');
            }
        } else {
            for (int index = 0; index < todos.size(); index++) {
                TodoItem todo = todos.get(index);
                if (!isCompleted(todo)) {
                    continue;
                }
                String summary = todo.getResultSummary();
                message.append("第 ").append(index + 1).append('/').append(todos.size()).append(" 步已完成：")
                        .append(summary == null || summary.isBlank() ? content(todo) : summary).append("。\n");
            }
        }
        message.append("\n当前执行第 ").append(currentIndex + 1).append('/').append(todos.size()).append(" 步：")
                .append(content(todos.get(currentIndex))).append("。");
        return message.toString();
    }

    private static int currentIndex(List<TodoItem> todos) {
        for (int index = 0; index < todos.size(); index++) {
            if (todos.get(index) != null && todos.get(index).getStatus() == TodoStatus.IN_PROGRESS) {
                return index;
            }
        }
        for (int index = 0; index < todos.size(); index++) {
            if (!isCompleted(todos.get(index)) && todos.get(index) != null
                    && todos.get(index).getStatus() != TodoStatus.CANCELLED) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isCompleted(TodoItem todo) {
        return todo != null && (todo.getStatus() == TodoStatus.COMPLETED || todo.getStatus() == TodoStatus.DONE);
    }

    private static String content(TodoItem todo) {
        return todo == null || todo.getContent() == null ? "" : todo.getContent();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
