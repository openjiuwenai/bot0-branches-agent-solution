/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.enforcing;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;

import java.util.Objects;

/**
 * Model-owned prompt injection state, isolated between both model instances and invocation threads.
 *
 * @since 2026-07
 */
public final class PromptInjectionState {
    private volatile InjectionMode configuredMode = SystemPromptInjectingModel.DEFAULT_MODE;
    private final ThreadLocal<State> invocationState = ThreadLocal.withInitial(() -> new State(configuredMode));

    /**
     * Sets the model-level default used when an invocation thread first accesses this state.
     *
     * @param mode configured model mode
     */
    public void setConfiguredMode(InjectionMode mode) {
        configuredMode = Objects.requireNonNull(mode, "mode");
        invocationState.get().mode = mode;
    }

    /**
     * Sets the injection mode for the current invocation thread.
     *
     * @param mode injection mode
     */
    public void setMode(InjectionMode mode) {
        invocationState.get().mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * Gets the injection mode for the current invocation thread.
     *
     * @return current injection mode
     */
    public InjectionMode getMode() {
        return invocationState.get().mode;
    }

    /**
     * Sets the prompt override consumed by the next model call on the current thread.
     *
     * @param override prompt override, or {@code null} to clear it
     */
    public void setPhaseOverride(String override) {
        invocationState.get().phaseOverride = override;
    }

    /**
     * Reads the prompt override without consuming it.
     *
     * @return current override, or {@code null}
     */
    public String peekPhaseOverride() {
        return invocationState.get().phaseOverride;
    }

    /**
     * Reads and clears the prompt override.
     *
     * @return previous override, or {@code null}
     */
    public String consumePhaseOverride() {
        State state = invocationState.get();
        String override = state.phaseOverride;
        state.phaseOverride = null;
        return override;
    }

    /**
     * Removes current-thread state so a later invocation starts from defaults.
     */
    public void reset() {
        configuredMode = SystemPromptInjectingModel.DEFAULT_MODE;
        invocationState.remove();
    }

    private static final class State {
        private InjectionMode mode;
        private String phaseOverride;

        private State(InjectionMode mode) {
            this.mode = mode;
        }
    }
}
