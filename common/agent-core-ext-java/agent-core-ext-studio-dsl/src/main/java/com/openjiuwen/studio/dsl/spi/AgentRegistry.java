/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Local agent dispatch table (A2A remote details FEAT-004).
 *
 * @since 2026-08-17
 */
public interface AgentRegistry {
    /**
     * find.
     *
     * @param agentId agentId
     * @return result
     */
    Optional<Function<Map<String, Object>, Map<String, Object>>> find(String agentId);
}
