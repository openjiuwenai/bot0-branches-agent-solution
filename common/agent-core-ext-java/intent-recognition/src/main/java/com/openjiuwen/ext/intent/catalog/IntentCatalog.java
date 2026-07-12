/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.catalog;

import com.openjiuwen.ext.intent.api.IntentCandidate;
import java.util.List;
import java.util.Map;

/** Immutable, compiled target catalog consumed by the recognizer. */
public final class IntentCatalog<T> {
    private final List<T> targets;
    private final List<String> targetKeys;
    private final List<IntentCandidate> candidates;
    private final Map<String, Integer> targetIndexByCandidateId;
    private final String catalogHash;
    private final String candidateFormatVersion;

    IntentCatalog(List<T> targets, List<String> targetKeys, List<IntentCandidate> candidates,
            Map<String, Integer> targetIndexByCandidateId, String catalogHash, String candidateFormatVersion) {
        this.targets = List.copyOf(targets);
        this.targetKeys = List.copyOf(targetKeys);
        this.candidates = List.copyOf(candidates);
        this.targetIndexByCandidateId = Map.copyOf(targetIndexByCandidateId);
        this.catalogHash = catalogHash;
        this.candidateFormatVersion = candidateFormatVersion;
    }

    public List<T> targets() {
        return targets;
    }

    public List<String> targetKeys() {
        return targetKeys;
    }

    public List<IntentCandidate> candidates() {
        return candidates;
    }

    public Map<String, Integer> targetIndexByCandidateId() {
        return targetIndexByCandidateId;
    }

    public String catalogHash() {
        return catalogHash;
    }

    public String candidateFormatVersion() {
        return candidateFormatVersion;
    }
}
