/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_WORKING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CustomRestA2ATaskResolverTest {
    @Test
    void contextIdIsStableIsolatedAndOpaque() {
        String value = CustomRestA2ATaskResolver.internalContextId(null, "customer-session");

        assertThat(value).startsWith("custom-rest:v1:").hasSize(58).doesNotContain("customer-session");
        assertThat(CustomRestA2ATaskResolver.internalContextId(null, "customer-session")).isEqualTo(value);
        assertThat(CustomRestA2ATaskResolver.internalContextId("", "customer-session")).isNotEqualTo(value);
        assertThat(CustomRestA2ATaskResolver.internalContextId("tenant-a", "customer-session")).isNotEqualTo(value);
        assertThat(CustomRestA2ATaskResolver.internalContextId(null, "other-session")).isNotEqualTo(value);
    }

    @Test
    void listsEveryPageFetchesFullTasksAndResumesFormalInputRequiredTask() {
        TaskStore store = mock(TaskStore.class);
        String contextId = CustomRestA2ATaskResolver.internalContextId(null, "session");
        Task summary = task("formal", contextId, TASK_STATE_INPUT_REQUIRED, List.of());
        Task shadow = task("shadow:remote:session", contextId, TASK_STATE_INPUT_REQUIRED, List.of());
        Task formal = task("formal", contextId, TASK_STATE_INPUT_REQUIRED, List.of(message(contextId)));
        when(store.list(any())).thenReturn(
                new ListTasksResult(List.of(summary), 2, 1, "next"),
                new ListTasksResult(List.of(shadow), 2, 1, null));
        when(store.get("formal")).thenReturn(formal);
        when(store.get("shadow:remote:session")).thenReturn(shadow);

        String taskId = new CustomRestA2ATaskResolver(store).resolveTaskId(null, contextId);

        assertThat(taskId).isEqualTo("formal");
        ArgumentCaptor<ListTasksParams> captor = ArgumentCaptor.forClass(ListTasksParams.class);
        verify(store, org.mockito.Mockito.times(2)).list(captor.capture());
        assertThat(captor.getAllValues()).extracting(ListTasksParams::pageToken).containsExactly(null, "next");
        assertThat(captor.getAllValues()).allSatisfy(params -> {
            assertThat(params.contextId()).isEqualTo(contextId);
            assertThat(params.pageSize()).isEqualTo(100);
            assertThat(params.historyLength()).isZero();
            assertThat(params.includeArtifacts()).isFalse();
            assertThat(params.tenant()).isNull();
        });
        verify(store, never()).save(any(), any(Boolean.class));
        verify(store, never()).delete(any());
    }

    @Test
    void resumesAndObservesFormalInputRequiredTaskWithoutHistory() {
        String contextId = CustomRestA2ATaskResolver.internalContextId(null, "session");
        TaskStore store = storeWith(task("formal", contextId, TASK_STATE_INPUT_REQUIRED, List.of()));
        CustomRestA2ATaskResolver resolver = new CustomRestA2ATaskResolver(store);

        assertThat(resolver.resolveTaskId(null, contextId)).isEqualTo("formal");
        assertThat(resolver.isObservableFormalParent("formal", contextId)).isTrue();
    }

    @Test
    void ignoresKnownTerminalTasksAndCreatesNew() {
        Task terminal = task("done", "ctx", TASK_STATE_COMPLETED, List.of(message("ctx")));
        TaskStore store = storeWith(terminal);

        assertThat(new CustomRestA2ATaskResolver(store).resolveTaskId("tenant", "ctx")).isNull();
    }

    @Test
    void rejectsBusyAmbiguousAndUnclassifiedTasks() {
        assertFailure(storeWith(task("working", "ctx", TASK_STATE_WORKING, List.of(message("ctx")))),
                "conversation_busy");
        assertFailure(storeWith(
                task("one", "ctx", TASK_STATE_INPUT_REQUIRED, List.of(message("ctx"))),
                task("two", "ctx", TASK_STATE_INPUT_REQUIRED, List.of(message("ctx")))),
                "conversation_task_ambiguous");
        assertFailure(storeWith(task("shadow:unknown-helper", "ctx", TASK_STATE_INPUT_REQUIRED,
                List.of(message("ctx")))),
                "conversation_task_conflict");
        assertFailure(storeWith(task("unknown-state", "ctx", TaskState.UNRECOGNIZED, List.of(message("ctx")))),
                "conversation_task_conflict");
    }

    @Test
    void repeatedPageTokenAndStoreFailureFailClosed() {
        TaskStore repeated = mock(TaskStore.class);
        when(repeated.list(any())).thenReturn(new ListTasksResult(List.of(), 0, 0, "same"));
        assertFailure(repeated, "task_store_unavailable");

        TaskStore failed = mock(TaskStore.class);
        when(failed.list(any())).thenThrow(new IllegalStateException("storage detail"));
        assertFailure(failed, "task_store_unavailable");
    }

    private static TaskStore storeWith(Task... tasks) {
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of(tasks)));
        for (Task task : tasks) {
            when(store.get(task.id())).thenReturn(task);
        }
        return store;
    }

    private static void assertFailure(TaskStore store, String code) {
        assertThatThrownBy(() -> new CustomRestA2ATaskResolver(store).resolveTaskId(null, "ctx"))
                .isInstanceOf(CustomRestFailure.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private static Task task(String id, String contextId, TaskState state, List<Message> history) {
        return Task.builder().id(id).contextId(contextId).status(new TaskStatus(state)).history(history).build();
    }

    private static Message message(String contextId) {
        return Message.builder().role(Message.Role.ROLE_USER).parts(new TextPart("hello"))
                .messageId("message-id").contextId(contextId).metadata(Map.of()).build();
    }
}
