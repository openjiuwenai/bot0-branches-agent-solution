/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/** 文本 Part。 */
public record TextPartSnapshot(String text) implements PartSnapshot {
    public TextPartSnapshot {
        text = text == null ? "" : text;
    }
}
