/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_AUTH_REQUIRED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_CANCELED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_FAILED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_REJECTED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_SUBMITTED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;

final class CustomRestA2ATaskResolver {
    private static final String CONTEXT_PREFIX = "custom-rest:v1:";
    private static final int PAGE_SIZE = 100;

    private final TaskStore taskStore;

    CustomRestA2ATaskResolver(TaskStore taskStore) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
    }

    static String internalContextId(String tenantId, String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        try {
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            if (tenantId == null) {
                framed.write(0);
            } else {
                framed.write(1);
                writeFramed(framed, tenantId);
            }
            writeFramed(framed, conversationId);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(framed.toByteArray());
            return CONTEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    String resolveTaskId(String tenantId, String internalContextId) {
        List<Task> tasks = loadTasks(tenantId, internalContextId);
        List<Task> activeFormalTasks = new ArrayList<>();
        for (Task task : tasks) {
            TaskState state = stateOf(task);
            if (isFormal(task)) {
                if (!isKnownTerminal(state)) {
                    activeFormalTasks.add(task);
                }
                continue;
            }
            if (isKnownShadow(task) || isKnownTerminal(state)) {
                continue;
            }
            throw new CustomRestFailure(409, "conversation_task_conflict",
                    "The conversation contains an unrecognized active task");
        }

        if (activeFormalTasks.isEmpty()) {
            return null;
        }
        if (activeFormalTasks.size() > 1) {
            throw new CustomRestFailure(409, "conversation_task_ambiguous",
                    "The conversation contains multiple active tasks");
        }
        Task task = activeFormalTasks.get(0);
        TaskState state = stateOf(task);
        if (state == TASK_STATE_INPUT_REQUIRED) {
            return task.id();
        }
        if (state == TASK_STATE_SUBMITTED || state == TASK_STATE_WORKING) {
            throw new CustomRestFailure(409, "conversation_busy",
                    "The conversation is currently processing another request");
        }
        if (state == TASK_STATE_AUTH_REQUIRED) {
            throw new CustomRestFailure(409, "conversation_not_resumable",
                    "The conversation cannot be resumed with a normal input message");
        }
        throw new CustomRestFailure(409, "conversation_task_conflict",
                "The conversation task state is unrecognized");
    }

    boolean isObservableFormalParent(String taskId, String internalContextId) {
        if (taskId == null) {
            return false;
        }
        try {
            Task task = taskStore.get(taskId);
            return task != null && internalContextId.equals(task.contextId()) && isFormal(task);
        } catch (RuntimeException exception) {
            throw new CustomRestFailure(503, "task_store_unavailable", "The task store is unavailable");
        }
    }

    private List<Task> loadTasks(String tenantId, String internalContextId) {
        try {
            List<Task> tasks = new ArrayList<>();
            Set<String> seenTokens = new HashSet<>();
            String pageToken = null;
            do {
                if (pageToken != null && !seenTokens.add(pageToken)) {
                    throw new IllegalStateException("TaskStore returned a repeated page token");
                }
                ListTasksParams params = ListTasksParams.builder()
                        .contextId(internalContextId)
                        .pageSize(PAGE_SIZE)
                        .pageToken(pageToken)
                        .historyLength(0)
                        .includeArtifacts(false)
                        .tenant(tenantId)
                        .build();
                ListTasksResult page = taskStore.list(params);
                if (page == null) {
                    throw new IllegalStateException("TaskStore returned a null page");
                }
                for (Task summary : page.tasks()) {
                    if (summary == null || summary.id() == null) {
                        continue;
                    }
                    Task current = taskStore.get(summary.id());
                    if (current != null && internalContextId.equals(current.contextId())) {
                        tasks.add(current);
                    }
                }
                String nextPageToken = page.nextPageToken();
                pageToken = nextPageToken == null || nextPageToken.isBlank() ? null : nextPageToken;
            } while (pageToken != null);
            return tasks;
        } catch (RuntimeException exception) {
            throw new CustomRestFailure(503, "task_store_unavailable", "The task store is unavailable");
        }
    }

    private static boolean isFormal(Task task) {
        return task.id() != null && !task.id().startsWith("shadow:");
    }

    private static boolean isKnownShadow(Task task) {
        return task.id() != null && task.id().startsWith("shadow:")
                && (task.history() == null || task.history().isEmpty());
    }

    private static TaskState stateOf(Task task) {
        return task.status() == null ? null : task.status().state();
    }

    private static boolean isKnownTerminal(TaskState state) {
        return state == TASK_STATE_COMPLETED || state == TASK_STATE_FAILED || state == TASK_STATE_CANCELED
                || state == TASK_STATE_REJECTED;
    }

    private static void writeFramed(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        output.writeBytes(bytes);
    }
}
