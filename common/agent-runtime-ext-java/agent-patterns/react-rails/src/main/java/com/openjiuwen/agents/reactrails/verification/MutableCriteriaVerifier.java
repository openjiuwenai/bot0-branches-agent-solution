/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe decorator over {@link CriteriaVerifier} that allows runtime verifier
 * swap without rail re-registration.
 *
 * <p>Key SDK workaround: since {@link CriteriaReplanBridgeRail} holds a
 * {@code final CriteriaVerifier verifier} field, and we cannot change the jar,
 * this wrapper is injected instead. The real verifier implementation is swapped
 * via {@link #update(CriteriaVerifier)} between invocations.
 *
 * <p>AtomicReference guarantees that a concurrent verify() call always sees a
 * consistent delegate — no partial-state reads during the swap.
 *
 * <p>IFF contract: new MutableCriteriaVerifier(null) → verify() throws NPE →
 * RED test. Strip the AtomicReference → delegate leak between threads → RED.
 */
public class MutableCriteriaVerifier implements CriteriaVerifier {

    private final AtomicReference<CriteriaVerifier> delegateRef;

    /**
     * @param initial the initial verifier implementation. Must not be null.
     */
    public MutableCriteriaVerifier(CriteriaVerifier initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial verifier must not be null");
        }
        this.delegateRef = new AtomicReference<>(initial);
    }

    /**
     * Atomically swap the delegate. Safe to call from any thread, including
     * while other threads are concurrently calling {@link #verify}.
     *
     * @param newDelegate the new verifier. Must not be null.
     */
    public void update(CriteriaVerifier newDelegate) {
        if (newDelegate == null) {
            throw new IllegalArgumentException("new delegate must not be null");
        }
        delegateRef.set(newDelegate);
    }

    /** Current delegate (for inspection). */
    public CriteriaVerifier current() {
        return delegateRef.get();
    }

    @Override
    public List<Violation> verify(List<String> successCriteria,
                                   String output,
                                   String decisionHistory) {
        return delegateRef.get().verify(successCriteria, output, decisionHistory);
    }
}
