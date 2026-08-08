/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Versioned immutable initialized intent directory.
 *
 * @param version suite catalog version
 * @param initializedIntents initialized directory
 *
 * @since 0.1.0
 */
public record IntentCatalogSnapshot(long version, InitializedIntents initializedIntents) {
}
