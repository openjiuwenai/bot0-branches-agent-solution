/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentCandidateLimits;
import com.openjiuwen.ext.intent.api.IntentTargetAdapter;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Builds an immutable, validated catalog from caller-owned targets. */
public final class IntentCatalogCompiler<T> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IntentRecognizerConfig config;

    public IntentCatalogCompiler(IntentRecognizerConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public IntentCatalog<T> compile(List<T> targets, IntentTargetAdapter<T> adapter) {
        Objects.requireNonNull(targets, "targets must not be null");
        Objects.requireNonNull(adapter, "adapter must not be null");
        if (targets.size() > config.maxTargets()) {
            throw new IllegalArgumentException("target count exceeds maxTargets=" + config.maxTargets());
        }

        List<T> snapshots = new ArrayList<>(targets.size());
        List<String> targetKeys = new ArrayList<>(targets.size());
        List<IntentCandidate> candidates = new ArrayList<>();
        Map<String, Integer> targetIndexByCandidateId = new LinkedHashMap<>();
        Set<String> uniqueTargetKeys = new HashSet<>();

        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            T snapshot = Objects.requireNonNull(adapter.snapshot(targets.get(targetIndex)),
                    "snapshot must not be null");
            String targetKey = normalizeRequired(adapter.targetKey(snapshot), "targetKey");
            if (!uniqueTargetKeys.add(targetKey)) {
                throw new IllegalArgumentException("duplicate targetKey: " + targetKey);
            }
            snapshots.add(snapshot);
            targetKeys.add(targetKey);

            IntentCandidateLimits limits = new IntentCandidateLimits(config.maxFieldLength(),
                    config.maxCandidateLength(), config.maxTagsPerCandidate(), config.maxExamplesPerCandidate());
            List<IntentCandidate> extracted = Objects.requireNonNull(adapter.candidates(targetIndex, snapshot, limits),
                    "adapter candidates must not be null");
            for (IntentCandidate candidate : extracted) {
                addCandidate(targetIndex, candidate, candidates, targetIndexByCandidateId);
            }
        }
        if (candidates.size() > config.maxCandidates()) {
            throw new IllegalArgumentException("candidate count exceeds maxCandidates=" + config.maxCandidates());
        }
        candidates.sort(Comparator.comparing(IntentCandidate::candidateId));

        return new IntentCatalog<>(snapshots, targetKeys, candidates, targetIndexByCandidateId,
                catalogHash(targetKeys, candidates), config.candidateFormatVersion());
    }

    private void addCandidate(int expectedTargetIndex, IntentCandidate candidate, List<IntentCandidate> candidates,
            Map<String, Integer> targetIndexByCandidateId) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (candidate.targetIndex() != expectedTargetIndex) {
            throw new IllegalArgumentException("candidate targetIndex does not match current target");
        }
        String candidateId = normalizeRequired(candidate.candidateId(), "candidateId");
        String document = normalizeRequired(candidate.document(), "candidate document");
        if (document.codePointCount(0, document.length()) > config.maxCandidateLength()) {
            throw new IllegalArgumentException(
                    "candidate document exceeds maxCandidateLength=" + config.maxCandidateLength());
        }
        if (targetIndexByCandidateId.putIfAbsent(candidateId, expectedTargetIndex) != null) {
            throw new IllegalArgumentException("duplicate candidateId: " + candidateId);
        }
        candidates.add(new IntentCandidate(expectedTargetIndex, candidateId, document));
        if (candidates.size() > config.maxCandidates()) {
            throw new IllegalArgumentException("candidate count exceeds maxCandidates=" + config.maxCandidates());
        }
    }

    private String catalogHash(List<String> targetKeys, List<IntentCandidate> candidates) {
        List<Map<String, Object>> canonicalTargets = new ArrayList<>();
        for (int targetIndex = 0; targetIndex < targetKeys.size(); targetIndex++) {
            List<Map<String, String>> targetCandidates = new ArrayList<>();
            for (IntentCandidate candidate : candidates) {
                if (candidate.targetIndex() == targetIndex) {
                    Map<String, String> canonicalCandidate = new TreeMap<>();
                    canonicalCandidate.put("candidateId", candidate.candidateId());
                    canonicalCandidate.put("document", candidate.document());
                    targetCandidates.add(canonicalCandidate);
                }
            }
            targetCandidates.sort(Comparator.comparing(value -> value.get("candidateId")));
            Map<String, Object> canonicalTarget = new TreeMap<>();
            canonicalTarget.put("candidates", targetCandidates);
            canonicalTarget.put("targetKey", targetKeys.get(targetIndex));
            canonicalTargets.add(canonicalTarget);
        }
        canonicalTargets.sort(Comparator.comparing(value -> String.valueOf(value.get("targetKey"))));
        Map<String, Object> canonicalCatalog = new TreeMap<>();
        canonicalCatalog.put("candidateFormatVersion", config.candidateFormatVersion());
        canonicalCatalog.put("targets", canonicalTargets);
        try {
            byte[] canonicalJson = OBJECT_MAPPER.writeValueAsBytes(canonicalCatalog);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to compute catalog hash", exception);
        }
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
