/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * TaskStore decorator; the delegate remains the source of truth and the callback observes writes.
 *
 * @since 2026-07-22
 */
public final class ProjectingTaskStore implements TaskStore {
    private final TaskStore delegate;
    private final Consumer<Task> observer;

    /**
     * Creates a new instance.
     *
     * @param delegate
     *            the delegate value
     * @param observer
     *            the observer value
     */
    public ProjectingTaskStore(TaskStore delegate, Consumer<Task> observer) {
        this.delegate = Objects.requireNonNull(delegate);
        this.observer = Objects.requireNonNull(observer);
    }

    /** {@inheritDoc} */
    @Override
    public void save(Task task, boolean overwrite) {
        delegate.save(task, overwrite);
        observer.accept(task);
    }

    /** {@inheritDoc} */
    @Override
    public Task get(String taskId) {
        return delegate.get(taskId);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String taskId) {
        delegate.delete(taskId);
    }

    /** {@inheritDoc} */
    @Override
    public ListTasksResult list(ListTasksParams params) {
        return delegate.list(params);
    }

    /**
     * Performs the delegate operation.
     *
     * @return the operation result
     */
    public TaskStore delegate() {
        return delegate;
    }
}
