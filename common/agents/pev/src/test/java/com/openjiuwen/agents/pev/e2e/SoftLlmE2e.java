/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import java.util.function.Supplier;

/**
 * Soft-observe harness for real-LLM e2e tests.
 *
 * <p>Soft-skip a test body when the LLM endpoint is unavailable
 * ({@link LlmClient.LlmUnavailableException}: timeout / I/O / non-200) — a runtime/env
 * outage is reported as an <b>honest skipped-with-reason</b>, never as a red test that
 * masks a real defect (honesty split, 铁律①). Other failures (assertions, logic bugs)
 * propagate unchanged so genuine regressions still surface.
 *
 * <p>Usage in a real-LLM e2e:
 * <pre>{@code
 *   String output = SoftLlmE2e.runSoft("claims-adjudication", () -> {
 *       Object out = agent.invoke(task, null);
 *       return String.valueOf(out);
 *   });
 * }</pre>
 */
final class SoftLlmE2e {
    private SoftLlmE2e() {
    }

    /**
     * Run a real-LLM test body; on {@link LlmClient.LlmUnavailableException} soft-skip the
     * test with an explicit reason. The test's own assertions still run after the body —
     * this only shields the invoke/LLM-bound portion from infra outages.
     *
     * @param tag test scenario label for the skip reason
     * @param body LLM-bound test body
     * @param <T> returned body type
     * @return body result when the LLM endpoint is available
     */
    static <T> T runSoft(String tag, Supplier<T> body) {
        try {
            return body.get();
        } catch (LlmClient.LlmUnavailableException e) {
            String reason = "[" + tag + "] LLM endpoint unavailable, soft-skip: " + e.getMessage();
            // assumeTrue(false) marks the test as SKIPPED (aborted) — honest, not green-not-red.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, reason);
            throw new AssertionError("unreachable after soft-skip");
        }
    }
}
