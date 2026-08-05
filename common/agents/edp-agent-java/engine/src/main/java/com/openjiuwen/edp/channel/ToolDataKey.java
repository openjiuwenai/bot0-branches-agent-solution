/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 */

package com.openjiuwen.edp.channel;

import java.util.Objects;

/**
 * ToolDataChannel 四元组隔离键。
 *
 * 按 tenantId + agentId + contextId + taskId 隔离数据。
 *
 * @since 2024-01-01
 *
 */

public class ToolDataKey {
    private final String tenantId;
    private final String agentId;
    private final String contextId;
    private final String taskId;

    public ToolDataKey(String tenantId, String agentId, String contextId, String taskId) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.contextId = contextId;
        this.taskId = taskId;
    }

    /**
     * Gets the tenant id.
     *
     * @return the result
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the agent id.
     *
     * @return the result
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * Gets the context id.
     *
     * @return the result
     */
    public String getContextId() {
        return contextId;
    }

    /**
     * Gets the task id.
     *
     * @return the result
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * 判断当前对象是否与指定对象相等。
     *
     * @param o 待比较的对象
     * @return 相等返回 true，否则返回 false
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolDataKey that)) {
            return false;
        }
        return Objects.equals(this.tenantId, that.tenantId)
                && Objects.equals(this.agentId, that.agentId)
                && Objects.equals(this.contextId, that.contextId)
                && Objects.equals(this.taskId, that.taskId);
    }

    /**
     * 返回当前对象的哈希值。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(tenantId, agentId, contextId, taskId);
    }

    /**
     * 返回当前对象的字符串表示。
     *
     * @return 字符串表示
     */
    @Override
    public String toString() {
        return "ToolDataKey{tenant=" + tenantId + ",agent=" + agentId + ",context=" + contextId + ",task=" + taskId
                + "}";
    }
}
