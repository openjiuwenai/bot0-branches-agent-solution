/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.api;

import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.ext.intent.catalog.IntentCatalog;
import com.openjiuwen.ext.intent.catalog.IntentCatalogCompiler;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import com.openjiuwen.ext.intent.reranker.RerankerIntentRecognizer;
import com.openjiuwen.ext.intent.trace.IntentTraceListener;
import java.util.List;
import java.util.Objects;

/** Factory for immutable, configured intent recognizers. */
public final class IntentRecognizers {
    private IntentRecognizers() {
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** Fluent initialization-only builder. */
    public static final class Builder<T> {
        private List<T> targets;
        private IntentTargetAdapter<T> targetAdapter;
        private Reranker reranker;
        private IntentRecognizerConfig config;
        private IntentTraceListener traceListener = IntentTraceListener.NO_OP;

        private Builder() {
        }

        public Builder<T> targets(List<T> values) {
            targets = values == null ? null : List.copyOf(values);
            return this;
        }

        public Builder<T> targetAdapter(IntentTargetAdapter<T> value) {
            targetAdapter = value;
            return this;
        }

        public Builder<T> reranker(Reranker value) {
            reranker = value;
            return this;
        }

        public Builder<T> config(IntentRecognizerConfig value) {
            config = value;
            return this;
        }

        public Builder<T> traceListener(IntentTraceListener value) {
            traceListener = value;
            return this;
        }

        public IntentRecognizer<T> build() {
            List<T> frozenTargets = Objects.requireNonNull(targets, "targets must not be null");
            IntentTargetAdapter<T> adapter = Objects.requireNonNull(targetAdapter, "targetAdapter must not be null");
            Reranker configuredReranker = Objects.requireNonNull(reranker, "reranker must not be null");
            IntentRecognizerConfig configured = Objects.requireNonNull(config, "config must not be null");
            IntentCatalog<T> catalog = new IntentCatalogCompiler<T>(configured).compile(frozenTargets, adapter);
            return new RerankerIntentRecognizer<>(catalog, configuredReranker, configured,
                    traceListener == null ? IntentTraceListener.NO_OP : traceListener);
        }
    }
}
