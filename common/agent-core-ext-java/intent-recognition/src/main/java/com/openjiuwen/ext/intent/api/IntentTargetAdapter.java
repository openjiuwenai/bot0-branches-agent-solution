/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import java.util.List;

/** Converts caller-owned target objects into immutable snapshots and scoring candidates. */
public interface IntentTargetAdapter<T> {
    T snapshot(T target);

    String targetKey(T target);

    List<IntentCandidate> candidates(int targetIndex, T target);

    default List<IntentCandidate> candidates(int targetIndex, T target, IntentCandidateLimits limits) {
        return candidates(targetIndex, target);
    }
}
