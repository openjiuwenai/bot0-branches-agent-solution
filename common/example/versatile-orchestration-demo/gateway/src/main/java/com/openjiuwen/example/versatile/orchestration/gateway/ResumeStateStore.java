/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Holds the latest INPUT_REQUIRED taskId per contextId so a follow-up request
 * on the same contextId is sent to the plan-agent as a resume (carrying taskId).
 * Entries are cleared on terminal status (completed/failed/canceled/rejected).
 *
 * @since 2026-07-08
 */
@Component
public class ResumeStateStore {
    private final Map<String, String> taskIdByContextId = new ConcurrentHashMap<>();

    /**
     * recordInputRequired
     *
     * @param contextId contextId
     * @param taskId taskId
     */
    public void recordInputRequired(String contextId, String taskId) {
        if (contextId != null && taskId != null) {
            taskIdByContextId.put(contextId, taskId);
        }
    }

    /**
     * clear
     *
     * @param contextId contextId
     */
    public void clear(String contextId) {
        if (contextId != null) {
            taskIdByContextId.remove(contextId);
        }
    }

    /**
     * openTaskId
     *
     * @param contextId contextId
     * @return Optional<String>
     */
    public Optional<String> openTaskId(String contextId) {
        if (contextId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskIdByContextId.get(contextId));
    }
}
