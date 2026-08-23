/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Structured Tool result a downstream handler returns when the resumed user input expresses a
 * different goal than the interrupted Tool.
 *
 * <p>
 * The suite never produces or consumes this signal. Detecting the change is the downstream
 * handler's responsibility; this type only fixes the wire shape so the caller and the handler
 * agree on it without sharing a literal. A caller that observes the signal is expected to build a
 * new semantic from {@link #LATEST_SEMANTIC_KEY} and resolve an intent again instead of ending the
 * turn.
 *
 * @since 0.1.0
 */
public final class IntentChangeSignal {
    /** Value of {@link #STATUS_KEY} identifying an intent change result. */
    public static final String STATUS = "INTENT_CHANGED";

    /** Result key carrying the status discriminator. */
    public static final String STATUS_KEY = "status";

    /** Result key carrying the newest user semantic. */
    public static final String LATEST_SEMANTIC_KEY = "latestSemantic";

    /** Result key carrying an optional human readable explanation. */
    public static final String MESSAGE_KEY = "message";

    private IntentChangeSignal() {
    }

    /**
     * Builds an intent change result.
     *
     * @param latestSemantic newest user semantic, must not be blank
     * @param message optional explanation, ignored when blank
     * @return immutable intent change result
     */
    public static Map<String, Object> of(String latestSemantic, String message) {
        if (latestSemantic == null || latestSemantic.isBlank()) {
            throw new IllegalArgumentException("latestSemantic must not be blank");
        }
        Map<String, Object> signal = new LinkedHashMap<>();
        signal.put(STATUS_KEY, STATUS);
        signal.put(LATEST_SEMANTIC_KEY, latestSemantic);
        if (message != null && !message.isBlank()) {
            signal.put(MESSAGE_KEY, message);
        }
        return Map.copyOf(signal);
    }

    /**
     * Returns whether a Tool result is an intent change signal.
     *
     * @param toolResult Tool result, only the decoded {@link Map} form is recognised
     * @return true when the result carries the intent change status
     */
    public static boolean matches(Object toolResult) {
        return toolResult instanceof Map<?, ?> result && STATUS.equals(result.get(STATUS_KEY));
    }

    /**
     * Extracts the newest user semantic from an intent change signal.
     *
     * @param toolResult Tool result, only the decoded {@link Map} form is recognised
     * @return newest semantic, or empty when the result is not a usable signal
     */
    public static Optional<String> latestSemantic(Object toolResult) {
        if (!matches(toolResult)) {
            return Optional.empty();
        }
        Object semantic = ((Map<?, ?>) toolResult).get(LATEST_SEMANTIC_KEY);
        if (!(semantic instanceof String value) || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
