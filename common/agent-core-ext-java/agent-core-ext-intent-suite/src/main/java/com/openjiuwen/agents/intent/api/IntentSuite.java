/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import com.openjiuwen.agents.intent.exception.IntentInitializationException;
import com.openjiuwen.agents.intent.exception.IntentMatchException;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentAction;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentCatalogSnapshot;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.agents.intent.spi.IntentInitializer;
import com.openjiuwen.agents.intent.spi.IntentMatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Public entry point for intent catalog replacement and single-intent resolution.
 *
 * @since 0.1.0
 */
public final class IntentSuite {
    private static final Logger log = LoggerFactory.getLogger(IntentSuite.class);

    private final IntentSuiteConfig config;
    private final IntentInitializer initializer;
    private final IntentMatcher matcher;
    private final AtomicReference<IntentCatalogSnapshot> current;
    private final ReentrantLock updateLock = new ReentrantLock();

    private IntentSuite(IntentSuiteConfig config, IntentInitializer initializer, IntentMatcher matcher,
            InitializedIntents initialIntents) {
        this.config = config;
        this.initializer = initializer;
        this.matcher = matcher;
        current = new AtomicReference<>(new IntentCatalogSnapshot(0L, initialIntents));
    }

    /**
     * Creates a strict suite builder.
     *
     * @param config static suite configuration
     * @return suite builder
     */
    public static Builder builder(IntentSuiteConfig config) {
        return new Builder(config);
    }

    /**
     * Returns the static suite configuration.
     *
     * @return suite configuration
     */
    public IntentSuiteConfig config() {
        return config;
    }

    /**
     * Resolves one intent and executes its bound result function.
     *
     * @param inputs intent Tool inputs
     * @param kwargs Tool execution kwargs
     * @return decision
     */
    public IntentDecision resolve(Map<String, Object> inputs, Map<String, Object> kwargs) {
        IntentCatalogSnapshot snapshot = current.get();
        IntentExecutionContext context;
        try {
            context = IntentExecutionContext.create(config, snapshot, inputs, kwargs);
        } catch (IllegalArgumentException | NullPointerException exception) {
            log.info("Intent resolution rejected invalid input catalogVersion={}", snapshot.version());
            return failed(null, "意图调用输入无效");
        }
        log.info("Intent resolution started catalogVersion={} candidates={} fallback={} semanticLength={}",
                snapshot.version(), snapshot.initializedIntents().matchableIntents().size(),
                snapshot.initializedIntents().fallback() != null, context.routingSemantic().length());

        Optional<IntentDefinition> matched;
        try {
            matched = Optional.ofNullable(matcher.match(context))
                    .orElseThrow(() -> new IntentMatchException("matcher result must not be null"));
        } catch (RuntimeException exception) {
            log.info("Intent matching failed catalogVersion={} reason={}", snapshot.version(), exception.getMessage());
            return failed(null, "意图匹配失败");
        }
        if (!isValidMatch(matched, snapshot.initializedIntents())) {
            log.info("Intent matcher returned an intent outside catalogVersion={}", snapshot.version());
            return failed(null, "意图匹配结果无效");
        }

        IntentDefinition selected = matched.orElse(snapshot.initializedIntents().fallback());
        if (selected == null) {
            log.info("Intent resolution completed status={} catalogVersion={}", IntentDecisionStatus.UNMATCHED,
                    snapshot.version());
            return new IntentDecision(IntentDecisionStatus.UNMATCHED, null, null, "意图未匹配");
        }
        IntentDecisionStatus status = matched.isPresent()
                ? IntentDecisionStatus.MATCHED
                : IntentDecisionStatus.FALLBACK;
        log.info("Intent selected status={} intentId={} catalogVersion={}", status, selected.id(), snapshot.version());
        context.select(selected);
        return applyResultFunction(status, selected, context);
    }

    /**
     * Replaces the complete dynamic catalog atomically.
     *
     * @param catalog complete catalog input
     * @return new suite catalog version
     */
    public long replaceCatalog(IntentCatalogInput catalog) {
        Objects.requireNonNull(catalog, "catalog");
        updateLock.lock();
        try {
            InitializedIntents initialized = initializer.initialize(config, catalog);
            validateInitialized(initialized, false);
            long nextVersion = current.get().version() + 1L;
            current.set(new IntentCatalogSnapshot(nextVersion, initialized));
            log.info("Intent catalog replaced version={} matchableIntents={} fallback={}", nextVersion,
                    initialized.matchableIntents().size(), initialized.fallback() != null);
            return nextVersion;
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * Returns the current immutable catalog snapshot.
     *
     * @return catalog snapshot
     */
    public IntentCatalogSnapshot snapshot() {
        return current.get();
    }

    private IntentDecision applyResultFunction(IntentDecisionStatus status, IntentDefinition selected,
            IntentExecutionContext context) {
        try {
            IntentAction action = selected.resultFunction().apply(context);
            if (!isValidAction(action)) {
                log.info("Intent result function returned invalid action intentId={}", selected.id());
                return failed(selected.id(), "意图动作无效");
            }
            log.info("Intent result function completed intentId={} actionType={}", selected.id(),
                    action.getClass().getSimpleName());
            return new IntentDecision(status, selected.id(), action, null);
        } catch (RuntimeException exception) {
            log.info("Intent result function failed intentId={} reason={}", selected.id(), exception.getMessage());
            return failed(selected.id(), "意图结果函数执行失败");
        }
    }

    private static boolean isValidMatch(Optional<IntentDefinition> matched, InitializedIntents intents) {
        if (matched.isEmpty()) {
            return true;
        }
        IntentDefinition selected = matched.orElseThrow();
        return intents.matchableIntents().stream().anyMatch(candidate -> candidate == selected);
    }

    private static boolean isValidAction(IntentAction action) {
        if (action instanceof ReturnAction returnAction) {
            return !(returnAction.result() instanceof Throwable);
        }
        if (action instanceof InvokeToolAction invokeAction) {
            return invokeAction.toolName() != null && !invokeAction.toolName().isBlank()
                    && invokeAction.arguments() != null;
        }
        return false;
    }

    private static IntentDecision failed(String intentId, String message) {
        return new IntentDecision(IntentDecisionStatus.FAILED, intentId, null, message);
    }

    private static void validateInitialized(InitializedIntents initialized, boolean initialCatalog) {
        if (initialized == null) {
            throw new IntentInitializationException("initializer returned null");
        }
        if (initialCatalog && (!initialized.matchableIntents().isEmpty() || initialized.fallback() != null)) {
            throw new IntentInitializationException("initial catalog must be empty");
        }
        Set<String> ids = new HashSet<>();
        for (IntentDefinition intent : initialized.matchableIntents()) {
            validateIntent(intent, true, ids);
        }
        if (initialized.fallback() != null) {
            validateIntent(initialized.fallback(), false, ids);
        }
    }

    private static void validateIntent(IntentDefinition intent, boolean matchable, Set<String> ids) {
        if (intent == null || intent.id() == null || intent.id().isBlank()) {
            throw new IntentInitializationException("intent id must not be blank");
        }
        if (!ids.add(intent.id())) {
            throw new IntentInitializationException("duplicate intent id: " + intent.id());
        }
        if (matchable && (intent.description() == null || intent.description().isBlank())) {
            throw new IntentInitializationException("matchable intent description must not be blank: " + intent.id());
        }
        if (intent.resultFunction() == null || intent.resultArguments() == null) {
            throw new IntentInitializationException("intent result binding is incomplete: " + intent.id());
        }
    }

    /**
     * Strict builder requiring explicit initializer and matcher instances.
     */
    public static final class Builder {
        private final IntentSuiteConfig config;

        private IntentInitializer initializer;
        private IntentMatcher matcher;

        private Builder(IntentSuiteConfig config) {
            this.config = Objects.requireNonNull(config, "config");
        }

        /**
         * Sets the initializer.
         *
         * @param value initializer
         * @return this builder
         */
        public Builder initializer(IntentInitializer value) {
            initializer = value;
            return this;
        }

        /**
         * Sets the matcher.
         *
         * @param value matcher
         * @return this builder
         */
        public Builder matcher(IntentMatcher value) {
            matcher = value;
            return this;
        }

        /**
         * Builds a suite with an initialized version-zero empty catalog.
         *
         * @return suite
         */
        public IntentSuite build() {
            IntentInitializer requiredInitializer = Objects.requireNonNull(initializer, "initializer");
            IntentMatcher requiredMatcher = Objects.requireNonNull(matcher, "matcher");
            InitializedIntents initial = requiredInitializer.initialize(config, IntentCatalogInput.empty());
            validateInitialized(initial, true);
            log.info("Intent suite initialized with empty catalog matchThreshold={}", config.matchThreshold());
            return new IntentSuite(config, requiredInitializer, requiredMatcher, initial);
        }
    }
}
