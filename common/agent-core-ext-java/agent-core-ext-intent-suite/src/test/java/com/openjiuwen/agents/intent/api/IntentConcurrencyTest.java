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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests that concurrent full replacements never leak partial catalogs into calls. */
class IntentConcurrencyTest {
    private static final int REPLACEMENTS = 60;
    private static final int RESOLVER_THREADS = 4;

    @Test
    void concurrentReplacementAndResolutionObserveOnlyCompleteVersions() throws Exception {
        IntentResultFunction echoSnapshot = context -> new ReturnAction(Map.of("version",
                context.catalogSnapshot().version(), "intentId", context.selectedIntent().orElseThrow().id()));
        IntentMatcher firstCandidate = context -> Optional
                .of(context.catalogSnapshot().initializedIntents().matchableIntents().get(0));
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(firstCandidate).build();
        assertThat(suite.replaceCatalog(versionedCatalog(1, echoSnapshot))).isEqualTo(1L);

        AtomicBoolean replacing = new AtomicBoolean(true);
        ExecutorService executor = Executors.newFixedThreadPool(RESOLVER_THREADS + 1);
        try {
            Future<?> replacer = executor.submit(() -> {
                for (int version = 2; version <= REPLACEMENTS; version++) {
                    assertThat(suite.replaceCatalog(versionedCatalog(version, echoSnapshot))).isEqualTo(version);
                }
                replacing.set(false);
            });
            List<Future<List<IntentDecision>>> resolvers = new ArrayList<>();
            for (int thread = 0; thread < RESOLVER_THREADS; thread++) {
                resolvers.add(executor.submit(() -> {
                    List<IntentDecision> decisions = new ArrayList<>();
                    while (replacing.get()) {
                        decisions.add(suite.resolve(Map.of("semantic", "route"), Map.of()));
                    }
                    return decisions;
                }));
            }
            replacer.get(10, TimeUnit.SECONDS);
            for (Future<List<IntentDecision>> resolver : resolvers) {
                for (IntentDecision decision : resolver.get(10, TimeUnit.SECONDS)) {
                    assertThat(decision.status()).isEqualTo(IntentDecisionStatus.MATCHED);
                    if (!(decision.action() instanceof ReturnAction returned)
                            || !(returned.result() instanceof Map<?, ?> observed)) {
                        throw new AssertionError("expected the snapshot echo result");
                    }
                    // One call must pair the catalog version with exactly that version's intent.
                    assertThat(observed.get("intentId")).isEqualTo("intent-" + observed.get("version"));
                    assertThat(decision.intentId()).isEqualTo(observed.get("intentId"));
                }
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static IntentCatalogInput versionedCatalog(int version, IntentResultFunction resultFunction) {
        return new IntentCatalogInput(List.of(),
                List.of(new CustomIntentRegistration("intent-" + version, "description " + version, resultFunction)),
                null);
    }
}
