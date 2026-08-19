/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Immutable ToolCall fields exposed to intent SPI implementations.
 *
 * @param id ToolCall identifier
 * @param name tool name
 * @param arguments serialized arguments
 *
 * @since 0.1.0
 */
public record IntentToolCallSnapshot(String id, String name, String arguments) {
}
