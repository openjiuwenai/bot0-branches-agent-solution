/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import com.openjiuwen.core.foundation.tool.Tool;

import java.util.Optional;

/**
 * Host tool registry for jiuwen.plugin apiId path (Studio FlowApi).
 *
 * @since 2026-08-17
 */

public interface ToolRegistry {

    /**
     * find.
     *
     * @param apiId apiId
     * @return result
     */

    Optional<Tool> find(String apiId);
}
