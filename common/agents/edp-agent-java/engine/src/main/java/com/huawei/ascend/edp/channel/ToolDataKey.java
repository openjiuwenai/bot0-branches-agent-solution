/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.channel;

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
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the agent id.
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * Gets the context id.
     */
    public String getContextId() {
        return contextId;
    }

    /**
     * Gets the task id.
     */
    public String getTaskId() {
        return taskId;
    }

    @Override
    /**
     * Checks equality with another object.
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
     */
    public String toString() {
        return "ToolDataKey{tenant=" + tenantId + ",agent=" + agentId + ",context=" + contextId + ",task=" + taskId
                + "}";
    }
}
