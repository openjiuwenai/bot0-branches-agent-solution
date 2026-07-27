/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.spi;

/**
 * ToolView 在 wire 上的中立表示（transport 层据此映射到 A2A {@code params.metadata.clientTools}）。
 * {@code name} 等于工具的 toolId。
 *
 * @since 2026-07-27
 */
public record ToolWireSpec(String name, String description, String inputSchema) {
}
