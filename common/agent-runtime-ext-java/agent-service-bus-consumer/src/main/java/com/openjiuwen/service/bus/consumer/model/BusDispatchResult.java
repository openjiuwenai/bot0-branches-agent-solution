/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.model;

import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;

/**
 * Normalized result returned by the shared A2A RequestHandler control plane.
 *
 * @since 2026-07-22
 */
public record BusDispatchResult(String taskId, EventKind response, Task task, boolean streamReady) {

    /**
     * Performs the response operation.
     *
     * @param response
     *            the response value
     *
     * @return the operation result
     */
    public static BusDispatchResult response(EventKind response) {
        Task task = response instanceof Task value ? value : null;
        String taskId = task != null ? task.id() : response instanceof Message message ? message.taskId() : null;
        return new BusDispatchResult(taskId, response, task, false);
    }

    /**
     * Performs the task operation.
     *
     * @param task
     *            the task value
     *
     * @return the operation result
     */
    public static BusDispatchResult task(Task task) {
        return new BusDispatchResult(task.id(), task, task, false);
    }

    /**
     * Performs the stream operation.
     *
     * @param taskId
     *            the taskId value
     *
     * @return the operation result
     */
    public static BusDispatchResult stream(String taskId) {
        return new BusDispatchResult(taskId, null, null, true);
    }
}
