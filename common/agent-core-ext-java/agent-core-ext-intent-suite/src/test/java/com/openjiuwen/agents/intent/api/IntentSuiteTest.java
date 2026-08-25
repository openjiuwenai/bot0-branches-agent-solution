/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.intent.exception.IntentInitializationException;
import com.openjiuwen.agents.intent.exception.IntentMatchException;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.FinishAction;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.model.NoIntentResultArguments;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.agents.intent.spi.IntentMatcher;
import com.openjiuwen.agents.intent.spi.IntentResultFunction;

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
    void rejectsMatchThresholdOutsideUnitRangeAtConstruction() {
        assertThatThrownBy(() -> IntentSuiteConfig.builder().matchThreshold(-0.01D).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("within [0, 1]");
        assertThatThrownBy(() -> IntentSuiteConfig.builder().matchThreshold(1.01D).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("within [0, 1]");
        assertThatThrownBy(() -> IntentSuiteConfig.builder().matchThreshold(Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IntentSuiteConfig.builder().matchThreshold(Double.POSITIVE_INFINITY).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsMatchThresholdUnitRangeBoundaries() {
        assertThat(IntentSuiteConfig.builder().matchThreshold(0.0D).build().matchThreshold()).isZero();
        assertThat(IntentSuiteConfig.builder().matchThreshold(1.0D).build().matchThreshold()).isEqualTo(1.0D);
    }

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

        AtomicReference<IntentDefinition> recursiveSelected = new AtomicReference<>();
        IntentSuite recursiveSuite = suite(context -> Optional.of(recursiveSelected.get()));
        recursiveSuite.replaceCatalog(new IntentCatalogInput(List.of(), List.of(new CustomIntentRegistration(
                "recursive", "recursive",
                context -> new InvokeToolAction("intent_match", Map.of("semantic", context.routingSemantic())))),
                null));
        recursiveSelected.set(recursiveSuite.snapshot().initializedIntents().matchableIntents().get(0));
        assertThat(recursiveSuite.resolve(Map.of("semantic", "again"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);
    }

    @Test
    void keepsFinishActionsWithAnswerTextAndRejectsBlankOnes() {
        AtomicReference<IntentDefinition> selected = new AtomicReference<>();
        IntentSuite terminalSuite = suite(context -> Optional.of(selected.get()));
        terminalSuite.replaceCatalog(catalog(
                new CustomIntentRegistration("terminal", "terminal", context -> new FinishAction("evidence", "answer")),
                null));
        selected.set(terminalSuite.snapshot().initializedIntents().matchableIntents().get(0));

        IntentDecision terminal = terminalSuite.resolve(Map.of("semantic", "out of scope"), Map.of());

        assertThat(terminal.status()).isEqualTo(IntentDecisionStatus.MATCHED);
        assertThat(terminal.action()).isEqualTo(new FinishAction("evidence", "answer"));

        IntentSuite blankSuite = suite(context -> Optional.of(selected.get()));
        blankSuite.replaceCatalog(catalog(
                new CustomIntentRegistration("blank", "blank", context -> new FinishAction("evidence", "  ")), null));
        selected.set(blankSuite.snapshot().initializedIntents().matchableIntents().get(0));

        assertThat(blankSuite.resolve(Map.of("semantic", "out of scope"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);
    }

    @Test
    void nullMatcherResultFailsWithoutReachingFallbackOrResultFunction() {
        AtomicReference<Boolean> fallbackInvoked = new AtomicReference<>(Boolean.FALSE);
        CustomIntentRegistration fallback = new CustomIntentRegistration("fallback", "fallback description",
                context -> {
                    fallbackInvoked.set(Boolean.TRUE);
                    return new ReturnAction("fallback");
                });
        IntentSuite suite = suite(context -> null);
        suite.replaceCatalog(catalog(registration("candidate"), fallback));

        IntentDecision decision = suite.resolve(Map.of("semantic", "pay"), Map.of());

        assertThat(decision.status()).isEqualTo(IntentDecisionStatus.FAILED);
        assertThat(decision.intentId()).isNull();
        assertThat(decision.action()).isNull();
        assertThat(fallbackInvoked.get()).isFalse();
    }

    @Test
    void convertsUnexpectedSpiRuntimeExceptionsToFailedDecisions() {
        IntentSuite matcherFailure = suite(context -> {
            throw new IllegalStateException("matcher defect");
        });
        matcherFailure.replaceCatalog(catalog(registration("candidate"), null));
        assertThat(matcherFailure.resolve(Map.of("semantic", "pay"), Map.of()).status())
                .isEqualTo(IntentDecisionStatus.FAILED);

        CustomIntentRegistration broken = new CustomIntentRegistration("broken", "broken", context -> {
            throw new IllegalStateException("result function defect");
        });
        AtomicReference<IntentDefinition> selected = new AtomicReference<>();
        IntentSuite resultFailure = suite(context -> Optional.of(selected.get()));
        resultFailure.replaceCatalog(catalog(broken, null));
        selected.set(resultFailure.snapshot().initializedIntents().matchableIntents().get(0));
        assertThat(resultFailure.resolve(Map.of("semantic", "pay"), Map.of()).status())
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
