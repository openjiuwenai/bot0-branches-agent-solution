/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Static configuration for an IntentSuite.
 *
 * @param matchThreshold minimum accepted reranker score
 * @param prompt model-visible intent prompt configuration
 * @param extensionOptions immutable options for custom SPI implementations
 *
 * @since 0.1.0
 */
public record IntentSuiteConfig(double matchThreshold, IntentPromptConfig prompt,
        Map<String, Object> extensionOptions) {
    private static final double DEFAULT_MATCH_THRESHOLD = 0.65D;

    /**
     * Creates an immutable validated config.
     */
    public IntentSuiteConfig {
        if (!Double.isFinite(matchThreshold)) {
            throw new IllegalArgumentException("matchThreshold must be finite");
        }
        prompt = Objects.requireNonNull(prompt, "prompt");
        extensionOptions = Map
                .copyOf(new LinkedHashMap<>(Objects.requireNonNull(extensionOptions, "extensionOptions")));
    }

    /**
     * Returns the default static configuration.
     *
     * @return default suite configuration
     */
    public static IntentSuiteConfig defaults() {
        return builder().build();
    }

    /**
     * Creates a configuration builder.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for IntentSuiteConfig.
     */
    public static final class Builder {
        private double matchThreshold = DEFAULT_MATCH_THRESHOLD;
        private IntentPromptConfig prompt = IntentPromptConfig.defaults();
        private Map<String, Object> extensionOptions = Map.of();

        /**
         * Sets the match threshold.
         *
         * @param value threshold
         * @return this builder
         */
        public Builder matchThreshold(double value) {
            matchThreshold = value;
            return this;
        }

        /**
         * Sets the prompt configuration.
         *
         * @param value prompt configuration
         * @return this builder
         */
        public Builder prompt(IntentPromptConfig value) {
            prompt = value;
            return this;
        }

        /**
         * Sets custom SPI options.
         *
         * @param value extension options
         * @return this builder
         */
        public Builder extensionOptions(Map<String, Object> value) {
            extensionOptions = value;
            return this;
        }

        /**
         * Builds a validated configuration.
         *
         * @return suite configuration
         */
        public IntentSuiteConfig build() {
            return new IntentSuiteConfig(matchThreshold, prompt, extensionOptions);
        }
    }
}
