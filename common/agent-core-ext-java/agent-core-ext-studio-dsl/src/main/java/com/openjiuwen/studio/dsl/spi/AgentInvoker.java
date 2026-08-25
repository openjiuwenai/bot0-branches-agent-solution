/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/**
 * Host-provided Agent invoker; remote A2A semantics FEAT-004.
 *
 * @since 2026-08-17
 */
public interface AgentInvoker {
    /**
     * invoke.
     * @param agentId agentId
     * @param inputs inputs
     * @throws Exception when the call fails
     */
    Map<String, Object> invoke(String agentId, Map<String, Object> inputs) throws Exception;
}
