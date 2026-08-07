/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.NoIntentResultArguments;
import com.openjiuwen.agents.intent.model.ReturnAction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Tests intent suite initialization, replacement, and resolution. */
class IntentSuiteTest {
    private static final IntentResultFunction RETURN_SELECTED = context -> new ReturnAction(
            context.selectedIntent().orElseThrow().id());

    @Test
    void strictBuilderRequiresBothSpiAndCreatesEmptyVersionZeroCatalog() {
        IntentSuiteConfig config = IntentSuiteConfig.defaults();

        assertThatThrownBy(() -> IntentSuite.builder(config).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> IntentSuite.builder(config).initializer(new DefaultIntentInitializer()).build())
                .isInstanceOf(NullPointerException.class);

        IntentSuite suite = suite(context -> Optional.empty());
        assertThat(suite.snapshot().version()).isZero();
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).isEmpty();
        assertThat(suite.resolve(Map.of("semantic", "anything"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.UNMATCHED);
    }

    @Test
    void resolvesMatchedFallbackUnmatchedAndFailedPaths() {
        AtomicReference<IntentDefinition> selected = new AtomicReference<>();
        IntentSuite matchedSuite = suite(context -> Optional.of(selected.get()));
        matchedSuite.replaceCatalog(catalog(registration("matched"), registration("fallback")));
        selected.set(matchedSuite.snapshot().initializedIntents().matchableIntents().get(0));

        IntentDecision matched = matchedSuite.resolve(Map.of("semantic", "pay"), Map.of());
        assertThat(matched.status()).isEqualTo(IntentDecisionStatus.MATCHED);
        if (!(matched.action() instanceof ReturnAction matchedAction)) {
            throw new AssertionError("expected matched return action");
        }
        assertThat(matchedAction.result()).isEqualTo("matched");

        IntentSuite fallbackSuite = suite(context -> Optional.empty());
        fallbackSuite.replaceCatalog(catalog(registration("candidate"), registration("fallback")));
        IntentDecision fallback = fallbackSuite.resolve(Map.of("semantic", "unknown"), Map.of());
        assertThat(fallback.status()).isEqualTo(IntentDecisionStatus.FALLBACK);
        if (!(fallback.action() instanceof ReturnAction fallbackAction)) {
            throw new AssertionError("expected fallback return action");
        }
        assertThat(fallbackAction.result()).isEqualTo("fallback");

        IntentSuite unmatchedSuite = suite(context -> Optional.empty());
        assertThat(unmatchedSuite.resolve(Map.of("semantic", "unknown"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.UNMATCHED);
        assertThat(unmatchedSuite.resolve(Map.of("semantic", " "), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);

        IntentSuite failedSuite = suite(context -> {
            throw new IntentMatchException("unavailable");
        });
        failedSuite.replaceCatalog(catalog(registration("candidate"), registration("fallback")));
        assertThat(failedSuite.resolve(Map.of("semantic", "pay"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);
    }

    @Test
    void rejectsForeignMatchAndInvalidActionsWithoutFallback() {
        IntentDefinition foreign = new IntentDefinition("foreign", "foreign", RETURN_SELECTED,
                NoIntentResultArguments.INSTANCE);
        IntentSuite foreignSuite = suite(context -> Optional.of(foreign));
        foreignSuite.replaceCatalog(catalog(registration("candidate"), registration("fallback")));
        assertThat(foreignSuite.resolve(Map.of("semantic", "pay"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);

        CustomIntentRegistration invalid = new CustomIntentRegistration("invalid", "invalid", context -> null);
        AtomicReference<IntentDefinition> selected = new AtomicReference<>();
        IntentSuite actionSuite = suite(context -> Optional.of(selected.get()));
        actionSuite.replaceCatalog(catalog(invalid, registration("fallback")));
        selected.set(actionSuite.snapshot().initializedIntents().matchableIntents().get(0));
        assertThat(actionSuite.resolve(Map.of("semantic", "bad"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);
    }

    @Test
    void failedReplacementKeepsPreviousSnapshot() {
        IntentSuite suite = suite(context -> Optional.empty());
        assertThat(suite.replaceCatalog(catalog(registration("one"), null))).isEqualTo(1L);

        IntentCatalogInput duplicate = new IntentCatalogInput(List.of(),
                List.of(registration("same"), registration("same")), null);
        assertThatThrownBy(() -> suite.replaceCatalog(duplicate)).isInstanceOf(IntentInitializationException.class);
        assertThat(suite.snapshot().version()).isEqualTo(1L);
        assertThat(suite.snapshot().initializedIntents().matchableIntents()).extracting(IntentDefinition::id)
                .containsExactly("one");
    }

    @Test
    void resolveKeepsCatalogSnapshotDuringReplacement() throws Exception {
        CountDownLatch matcherEntered = new CountDownLatch(1);
        Semaphore continueMatcher = new Semaphore(0);
        AtomicReference<IntentDefinition> selected = new AtomicReference<>();
        AtomicReference<Long> observedResultVersion = new AtomicReference<>();
        IntentMatcher matcher = context -> {
            selected.set(context.catalogSnapshot().initializedIntents().matchableIntents().get(0));
            matcherEntered.countDown();
            continueMatcher.acquireUninterruptibly();
            return Optional.of(selected.get());
        };
        IntentResultFunction result = context -> {
            observedResultVersion.set(context.catalogSnapshot().version());
            return new ReturnAction(context.selectedIntent().orElseThrow().id());
        };
        IntentSuite suite = suite(matcher);
        suite.replaceCatalog(
                new IntentCatalogInput(List.of(), List.of(new CustomIntentRegistration("old", "old", result)), null));

        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            Future<IntentDecision> decision = executor.submit(() -> suite.resolve(Map.of("semantic", "old"), Map.of()));
            assertThat(matcherEntered.await(2, TimeUnit.SECONDS)).isTrue();
            suite.replaceCatalog(catalog(registration("new"), null));
            continueMatcher.release();

            assertThat(decision.get(2, TimeUnit.SECONDS).intentId()).isEqualTo("old");
            assertThat(observedResultVersion.get()).isEqualTo(1L);
            assertThat(suite.snapshot().version()).isEqualTo(2L);
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static IntentSuite suite(IntentMatcher matcher) {
        return IntentSuite.builder(IntentSuiteConfig.defaults()).initializer(new DefaultIntentInitializer())
                .matcher(matcher).build();
    }

    private static IntentCatalogInput catalog(CustomIntentRegistration candidate, CustomIntentRegistration fallback) {
        return new IntentCatalogInput(List.of(), candidate == null ? List.of() : List.of(candidate), fallback);
    }

    private static CustomIntentRegistration registration(String id) {
        return new CustomIntentRegistration(id, id + " description", RETURN_SELECTED);
    }

}
