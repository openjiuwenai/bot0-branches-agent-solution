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
    /**
     * 注册句柄。
     *
     * @param registered LocalTool.Registered
     * @return 注册句柄
     */
    Registration register(LocalTool.Registered registered);

    /**
     * 注册句柄。
     *
     * @param descriptor LocalToolDescriptor
     * @param fn ToolExecutionRecord>
     * @return 注册句柄
     */
    default Registration register(LocalToolDescriptor descriptor,
                                  BiFunction<ToolInvocation, ToolExecutionContext, ToolExecutionRecord> fn) {
        return register(LocalTool.of(descriptor, fn));
    }

    /**
     * 注销指定工具。
     *
     * @param toolId 工具标识
     */
    void unregister(String toolId);

    /**
     * 查找已注册的工具。
     *
     * @param toolId 工具标识
     * @return 已注册工具（未找到返回 empty）
     */
    Optional<LocalTool.Registered> find(String toolId);

    /**
     * 工具描述符列表。
     *
     * @return 工具描述符列表
     */
    List<LocalToolDescriptor> descriptors();

    /**
     * 工具视图。
     *
     * @param effectivePolicy ToolExposurePolicy
     * @return 工具视图
     */
    ToolView toolView(ToolExposurePolicy effectivePolicy);

    /**
     * 反注册句柄。
     */
    interface Registration extends AutoCloseable {
        /**
         * 工具标识。
         *
         * @return 工具标识
         */
        String toolId();

        @Override
        void close();
    }
}
