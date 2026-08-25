/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import java.util.Map;

/**
 * Host-provided MCP tool invoker (Studio FlowMcp).
 *
 * @since 2026-08-17
 */
public interface McpToolInvoker {
    /**
     * invoke.
     *
     * @param server server
     * @param tool tool
     * @param arguments arguments
     * @return result
     * @throws Exception when the call fails
     */
    Map<String, Object> invoke(String server, String tool, Map<String, Object> arguments) throws Exception;
}
