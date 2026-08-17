/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/**
 * 文本 Part。
 *
 * @since 2026-07-27
 */
public record TextPartSnapshot(String text) implements PartSnapshot {
    public TextPartSnapshot {
        text = text == null ? "" : text;
    }
}
