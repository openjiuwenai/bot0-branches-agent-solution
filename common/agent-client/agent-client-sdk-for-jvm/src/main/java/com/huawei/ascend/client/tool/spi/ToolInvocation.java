/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次被远端驱动的本地工具调用（FEAT-007）。由 SDK 从服务端 {@code _interrupt} 投影解析而来，
 * 传入 {@link LocalTool#execute}。
 *
 * <p>{@code toolCallId} 是本次工具调用的幂等主键，SDK 依据它做"最多执行一次"去重。
 *
 * @since 2026-07-27
 */
public record ToolInvocation(
        String toolCallId,
        String toolId,
        Map<String, Object> arguments,
        Duration deadline) {

    public ToolInvocation {
        arguments = (arguments == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
