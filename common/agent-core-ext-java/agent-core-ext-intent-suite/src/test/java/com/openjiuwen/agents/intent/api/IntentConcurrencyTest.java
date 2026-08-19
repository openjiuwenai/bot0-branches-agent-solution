/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.ReturnAction;
import com.openjiuwen.agents.intent.spi.IntentMatcher;
import com.openjiuwen.agents.intent.spi.IntentResultFunction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tests that concurrent full replacements never leak partial catalogs into calls. */
class IntentConcurrencyTest {
    private static final int REPLACEMENTS = 60;
    private static final int RESOLVER_THREADS = 4;

    @Test
    void concurrentReplacementAndResolutionObserveOnlyCompleteVersions() throws Exception {
        CountDownLatch resolversEnteredMatcher = new CountDownLatch(RESOLVER_THREADS);
        CountDownLatch replacementsCompleted = new CountDownLatch(1);
        IntentResultFunction echoSnapshot = context -> new ReturnAction(Map.of("version",
                context.catalogSnapshot().version(), "intentId", context.selectedIntent().orElseThrow().id()));
        IntentMatcher firstCandidate = context -> {
            resolversEnteredMatcher.countDown();
            awaitOrFail(replacementsCompleted, "catalog replacements did not complete");
            return Optional.of(context.catalogSnapshot().initializedIntents().matchableIntents().get(0));
        };
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(firstCandidate).build();
        assertThat(suite.replaceCatalog(versionedCatalog(1, echoSnapshot))).isEqualTo(1L);

        ExecutorService executor = new ThreadPoolExecutor(RESOLVER_THREADS + 1, RESOLVER_THREADS + 1, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<Future<IntentDecision>> resolvers = new ArrayList<>();
            for (int thread = 0; thread < RESOLVER_THREADS; thread++) {
                resolvers.add(executor.submit(() -> suite.resolve(Map.of("semantic", "route"), Map.of())));
            }
            Future<?> replacer = executor.submit(() -> {
                try {
                    awaitOrFail(resolversEnteredMatcher, "resolvers did not enter matcher");
                    for (int version = 2; version <= REPLACEMENTS; version++) {
                        assertThat(suite.replaceCatalog(versionedCatalog(version, echoSnapshot))).isEqualTo(version);
                    }
                } finally {
                    replacementsCompleted.countDown();
                }
            });
            replacer.get(10, TimeUnit.SECONDS);
            for (Future<IntentDecision> resolver : resolvers) {
                assertDecision(resolver.get(10, TimeUnit.SECONDS), 1L);
            }
            assertThat(suite.snapshot().version()).isEqualTo(REPLACEMENTS);
            assertDecision(suite.resolve(Map.of("semantic", "route"), Map.of()), REPLACEMENTS);
        } finally {
            replacementsCompleted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitOrFail(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(timeoutMessage);
            }
        } catch (InterruptedException exception) {
            throw new AssertionError("concurrent test interrupted", exception);
        }
    }

    private static void assertDecision(IntentDecision decision, long expectedVersion) {
        assertThat(decision.status()).isEqualTo(IntentDecisionStatus.MATCHED);
        assertThat(decision.intentId()).isEqualTo("intent-" + expectedVersion);
        if (!(decision.action() instanceof ReturnAction returned)
                || !(returned.result() instanceof Map<?, ?> observed)) {
            throw new AssertionError("expected the snapshot echo result");
        }
        assertThat(observed.get("version")).isEqualTo(expectedVersion);
        assertThat(observed.get("intentId")).isEqualTo("intent-" + expectedVersion);
    }

    private static IntentCatalogInput versionedCatalog(int version, IntentResultFunction resultFunction) {
        return new IntentCatalogInput(List.of(),
                List.of(new CustomIntentRegistration("intent-" + version, "description " + version, resultFunction)),
                null);
    }
}
