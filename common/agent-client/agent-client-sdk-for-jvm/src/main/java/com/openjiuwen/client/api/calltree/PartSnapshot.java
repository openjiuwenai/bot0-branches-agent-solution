/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/** 调用树 Artifact Part 的只读投影。 */
public sealed interface PartSnapshot permits TextPartSnapshot, DataPartSnapshot {
}
