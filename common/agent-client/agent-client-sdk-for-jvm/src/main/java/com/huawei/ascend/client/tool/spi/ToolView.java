/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.util.List;

/**
 * 客户端向服务端投影的"当前可见本地工具集合"（FEAT-007 §L-04）。
 *
 * <p>它是"注册表 ∩ 暴露策略"的结果，随每次调用（或续传）一并上报给网关/runtime，
 * 使远端智能体得以感知并选择这些 client 工具。到 wire 的具体映射（clientTools 数组）由 transport 层完成。
 *
 * @param tools tools
 * @since 2026-07-27
 */
public record ToolView(List<LocalToolDescriptor> tools) {
    public ToolView {
        tools = List.copyOf(tools);
    }

    /**
     * 是否为空。
     *
     * @return 为空返回 true
     */
    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 工具数量。
     *
     * @return 工具数量
     */
    public int size() {
        return tools.size();
    }
}
