/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

/** A scorer-facing intent document associated with one catalog target. */
public record IntentCandidate(int targetIndex, String candidateId, String document) {
}
