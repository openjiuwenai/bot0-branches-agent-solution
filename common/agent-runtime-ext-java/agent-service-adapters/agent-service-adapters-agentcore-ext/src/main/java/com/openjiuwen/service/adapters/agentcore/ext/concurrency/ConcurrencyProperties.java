/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for runtime concurrency control (DFX-002).
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.concurrency")
public class ConcurrencyProperties {

    /**
     * Maximum concurrent tasks. {@code -1} means unlimited.
     */
    private int maxConcurrentTasks = -1;

    /**
     * Returns the maximum concurrent tasks.
     *
     * @return max concurrent tasks; {@code -1} means unlimited
     */
    public int getMaxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    /**
     * Sets the maximum concurrent tasks.
     *
     * @param maxConcurrentTasks max concurrent tasks; {@code -1} means unlimited
     */
    public void setMaxConcurrentTasks(int maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }
}
