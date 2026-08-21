/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;
import java.util.Optional;

/** Maps standard A2A responses to the outcome consumed by Runtime orchestration. */
final class RemoteCallOutcomeMapper {
    Optional<RemoteCallOutcome> mapTask(String taskId, TaskState state, String statusText, Task task,
            boolean callbackMode) {
        String normalizedStatusText = statusText == null ? "" : statusText;
        if (callbackMode && state != null && !state.isFinal()) {
            return Optional.of(new RemoteCallOutcome(taskId, TaskState.TASK_STATE_INPUT_REQUIRED,
                    "INPUT_REQUIRED", null, "Remote callback pending"));
        }
        if (state != null && state.isInterrupted()) {
            String inputPrompt = normalizedStatusText.isBlank()
                    ? "Remote agent requires input" : normalizedStatusText;
            return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), null, inputPrompt));
        }
        if (state == null || !state.isFinal()) {
            return Optional.empty();
        }
        String taskText = task == null ? "" : A2aPartContent.extractTaskResult(task);
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? normalizedStatusText : taskText)
                : (normalizedStatusText.isBlank() ? taskText : normalizedStatusText);
        return Optional.of(new RemoteCallOutcome(taskId, state, resultCategory(state), resultText, null));
    }

    Optional<RemoteCallOutcome> mapTask(Task task, boolean callbackMode) {
        if (task == null || task.status() == null) {
            return Optional.empty();
        }
        String statusText = task.status().message() == null ? "" : extractText(task.status().message().parts());
        return mapTask(task.id(), task.status().state(), statusText, task, callbackMode);
    }

    Optional<RemoteCallOutcome> mapMessage(Message message) {
        if (message == null) {
            return Optional.empty();
        }
        return Optional.of(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
                A2aPartContent.extract(message.parts()), null));
    }

    static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_" + state.name().replaceFirst("^TASK_STATE_", "");
        };
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }
}
