/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 本地工具注册表（FEAT-007 SPI）。以 {@code toolId} 为唯一键管理工具的注册与查找。
 *
 * <p>注册本身不等于暴露：注册后默认不对服务端可见，需配合 {@link ToolExposurePolicy} 才会进入 ToolView。
 *
 * @since 2026-07-27
 */
public interface LocalToolRegistry {

    /** 注册（或替换同 toolId 的）工具，返回可用于反注册的句柄。 */
    Registration register(LocalTool.Registered registered);

    /** 便捷注册：同步实现。 */
    default Registration register(LocalToolDescriptor descriptor,
                                  BiFunction<ToolInvocation, ToolExecutionContext, ToolExecutionRecord> fn) {
        return register(LocalTool.of(descriptor, fn));
    }

    void unregister(String toolId);

    Optional<LocalTool.Registered> find(String toolId);

    /** 所有已注册工具的描述符（不含暴露判定）。 */
    List<LocalToolDescriptor> descriptors();

    /** 依据生效的暴露策略，计算当前应上报的 ToolView。 */
    ToolView toolView(ToolExposurePolicy effectivePolicy);

    /** 反注册句柄。 */
    interface Registration extends AutoCloseable {
        String toolId();

        @Override
        void close();
    }
}
