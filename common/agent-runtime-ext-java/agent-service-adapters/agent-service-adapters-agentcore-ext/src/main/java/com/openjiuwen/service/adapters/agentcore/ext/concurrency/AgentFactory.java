/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

/**
 * SPI for creating and destroying per-Task Agent instances (DFX-002).
 *
 * <p>Implemented by business agent projects (e.g. {@code EdpAgentFactory}) to
 * define how a fresh Agent object is created with business configuration.
 *
 * @since 0.1.0
 */
public interface AgentFactory {

    /**
     * Create a new Agent instance.
     *
     * @return a freshly created Agent object (never {@code null})
     */
    Object create();

    /**
     * Release resources held by an Agent instance.
     *
     * @param agent the Agent object to destroy
     */
    void destroy(Object agent);
}
