/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable compatibility and trust policy applied while compiling A2A cards. */
public record A2AEligibilityPolicy(Set<String> supportedProtocolBindings, Set<String> supportedProtocolVersions,
        Set<String> supportedRequiredExtensionUris, Set<String> acceptedInputModes,
        A2ASecurityRequirementEvaluator securityEvaluator, A2AContentTrustEvaluator contentTrustEvaluator) {

    public A2AEligibilityPolicy {
        supportedProtocolBindings = normalizeSet(supportedProtocolBindings, Normalization.UPPERCASE);
        supportedProtocolVersions = normalizeSet(supportedProtocolVersions, Normalization.EXACT);
        supportedRequiredExtensionUris = normalizeSet(supportedRequiredExtensionUris, Normalization.EXACT);
        acceptedInputModes = normalizeSet(acceptedInputModes, Normalization.LOWERCASE);
        if (supportedProtocolBindings.isEmpty()) {
            throw new IllegalArgumentException("supportedProtocolBindings must not be empty");
        }
        if (supportedProtocolVersions.isEmpty()) {
            throw new IllegalArgumentException("supportedProtocolVersions must not be empty");
        }
        if (!acceptedInputModes.contains("text/plain")) {
            throw new IllegalArgumentException("acceptedInputModes must contain text/plain");
        }
        securityEvaluator = Objects.requireNonNull(securityEvaluator, "securityEvaluator must not be null");
        contentTrustEvaluator = Objects.requireNonNull(contentTrustEvaluator, "contentTrustEvaluator must not be null");
    }

    private static Set<String> normalizeSet(Set<String> values, Normalization normalization) {
        Objects.requireNonNull(values, "policy set must not be null");
        return values.stream().map(value -> normalize(value, normalization)).collect(Collectors.toUnmodifiableSet());
    }

    static String normalize(String value, Normalization normalization) {
        if (value == null) {
            throw new IllegalArgumentException("policy values must not be null");
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("policy values must not be blank");
        }
        if (normalization == Normalization.UPPERCASE) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        if (normalization == Normalization.LOWERCASE) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }

    enum Normalization {
        EXACT,
        UPPERCASE,
        LOWERCASE
    }
}
