/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.tools.FileTodoStorage;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds the Intent Agent's actual task-plan progress to remote input requests. */
public final class BankPlanProgressRail extends AgentRail {
    /** Runs after intent routing and before the A2A delegate interrupt Rail. */
    public static final int PRIORITY = 100;

    private static final String A2A_DELEGATE = "a2a_delegate";
    private static final String PARENT_PROGRESS = "parentProgress";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(BankPlanProgressRail.class);

    private final FileTodoStorage todoStorage;

    /**
     * Creates a progress Rail for the DeepAgent workspace.
     *
     * @param workspace Intent Agent workspace
     */
    public BankPlanProgressRail(Path workspace) {
        todoStorage = new FileTodoStorage(workspace.resolve(".todo"));
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
        List<TodoItem> todos;
        try {
            todos = todoStorage.load(sessionId);
        } catch (IOException exception) {
            log.info("BANK_DEMO_PLAN_PROGRESS unavailable sessionId={} reason={}", sessionId,
                    exception.getMessage());
            return;
        }
        if (todos.size() < 2) {
            return;
        }
        Map<String, Object> arguments;
        try {
            arguments = arguments(inputs);
        } catch (IllegalArgumentException exception) {
            log.info("BANK_DEMO_PLAN_PROGRESS skipped sessionId={} reason={}", sessionId,
                    exception.getMessage());
            return;
        }
        String progress = progressMessage(todos);
        if (progress.isBlank()) {
            return;
        }
        arguments.put(PARENT_PROGRESS, progress);
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall != null) {
            try {
                toolCall.setArguments(OBJECT_MAPPER.writeValueAsString(arguments));
            } catch (JsonProcessingException exception) {
                log.info("BANK_DEMO_PLAN_PROGRESS skipped sessionId={} reason=serialization", sessionId);
                return;
            }
        }
        inputs.setToolArgs(arguments);
        log.info("BANK_DEMO_PLAN_PROGRESS sessionId={} taskCount={} currentStep={}", sessionId, todos.size(),
                currentIndex(todos) + 1);
    }

    private static Map<String, Object> arguments(ToolCallInputs inputs) {
        Object source = inputs.getToolArgs();
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        if (!(source instanceof String json) || json.isBlank()) {
            throw new IllegalArgumentException("A2A delegate arguments are missing");
        }
        try {
            return OBJECT_MAPPER.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("A2A delegate arguments are invalid", exception);
        }
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
}
