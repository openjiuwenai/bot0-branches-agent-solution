/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 */

package com.openjiuwen.edp.channel;

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

    @Override
    /**
     * Checks equality with another object.
     *
     * @param o the o value
     * @return the result
     */
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolDataKey)) {
            return false;
        }
        ToolDataKey that = (ToolDataKey) o;
        return tenantId.equals(that.tenantId) && agentId.equals(that.agentId) && contextId.equals(that.contextId)
                && taskId.equals(that.taskId);
    }

    @Override
    /**
     * Returns the hash code.
     *
     * @return the result
     */
    public int hashCode() {
        int result = tenantId.hashCode();
        result = 31 * result + agentId.hashCode();
        result = 31 * result + contextId.hashCode();
        result = 31 * result + taskId.hashCode();
        return result;
    }

    @Override
    /**
     * Returns a string representation of the key.
     *
     * @return the result
     */
    public String toString() {
        return "ToolDataKey{tenant=" + tenantId + ",agent=" + agentId + ",context=" + contextId + ",task=" + taskId
                + "}";
    }
}
