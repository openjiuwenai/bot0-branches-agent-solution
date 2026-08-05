/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for EDPA-alpha.
 *
 * <p>Bind via {@code edpa.*} prefix in application.properties/yml:
 * <pre>
 * edpa.enabled=true                    # master switch (default false)
 * edpa.explore-rounds=2                # max explore rounds (default 2)
 * edpa.max-subagents=3                 # max subagents per explore (default 3)
 * edpa.explore-timeout-millis=60000    # per-round timeout (default 60000)
 * edpa.criteria=关键词1,关键词2          # success criteria for verify gate
 * edpa.max-replan=2                    # max replan count (default 2)
 * </pre>
 *
 * @since 2026-07
 */
@ConfigurationProperties(prefix = "edpa")
public class EdpaProperties {
    /** Master switch — EDPA rails are only registered when true. Default false. */
    private boolean enabled = false;

    /**
     * Explore activation mode: "tool" (LLM-driven, default) or "rail"
     * (fixed-round pushSteering). Tool mode = Species E (GEPA-converged);
     * rail mode = ExploreRail backup (kept for evolvability, not deleted).
     */
    private String exploreMode = "tool";

    /** Maximum exploration rounds before switching to Plan phase. Default 2. */
    private int exploreRounds = 2;

    /** Maximum subagents that can be spawned during Explore. Default 3. */
    private int maxSubagents = 3;

    /** Per-round explore timeout in milliseconds. Default 60000. */
    private int exploreTimeoutMillis = 60_000;

    /** Comma-separated success criteria for the verify gate. Empty = skip verify rail. */
    private List<String> criteria = new ArrayList<>();

    /** Maximum replan count for the shared replan budget. Default 2. */
    private int maxReplan = 2;

    /**
     * Enable the proactive convergence verifier (D-stage Decision verifier).
     * When true (and criteria non-empty), {@link com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail}
     * monitors tool-round criteria coverage and pushes convergence steering when the
     * trajectory flatlines below the threshold. Default false (opt-in).
     */
    private boolean proactiveConvergenceEnabled = false;

    /**
     * Number of consecutive flatlined tool rounds before the proactive convergence
     * verifier fires. Default 2 (needs stallWindow+1 tool rounds to trigger).
     */
    private int proactiveConvergenceStallWindow = 2;

    /**
     * Default constructor required for Spring {@code @ConfigurationProperties} binding.
     * All fields keep their documented defaults; setters are invoked by the binder.
     */
    public EdpaProperties() {
        // no-arg constructor for property binding; defaults are field initializers
    }

    /**
     * Returns whether EDPA rails are registered.
     *
     * @return true if EDPA is enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the explore activation mode.
     *
     * @return the explore mode, either "tool" (LLM-driven) or "rail" (fixed-round)
     */
    public String getExploreMode() {
        return exploreMode;
    }

    /**
     * Sets the explore activation mode.
     *
     * @param exploreMode the explore mode to set — "tool" (LLM-driven, default) or "rail" (fixed-round)
     */
    public void setExploreMode(String exploreMode) {
        this.exploreMode = exploreMode;
    }

    /**
     * Sets the EDPA master switch.
     *
     * @param enabled true to enable EDPA, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the maximum number of exploration rounds.
     *
     * @return the maximum explore rounds before switching to the Plan phase
     */
    public int getExploreRounds() {
        return exploreRounds;
    }

    /**
     * Sets the maximum number of exploration rounds.
     *
     * @param exploreRounds the maximum explore rounds before switching to the Plan phase
     */
    public void setExploreRounds(int exploreRounds) {
        this.exploreRounds = exploreRounds;
    }

    /**
     * Returns the maximum number of subagents that can be spawned.
     *
     * @return the maximum subagents that can be spawned during Explore
     */
    public int getMaxSubagents() {
        return maxSubagents;
    }

    /**
     * Sets the maximum number of subagents that can be spawned.
     *
     * @param maxSubagents the maximum subagents that can be spawned during Explore
     */
    public void setMaxSubagents(int maxSubagents) {
        this.maxSubagents = maxSubagents;
    }

    /**
     * Returns the per-round explore timeout.
     *
     * @return the per-round explore timeout in milliseconds
     */
    public int getExploreTimeoutMillis() {
        return exploreTimeoutMillis;
    }

    /**
     * Sets the per-round explore timeout.
     *
     * @param exploreTimeoutMillis the per-round explore timeout in milliseconds
     */
    public void setExploreTimeoutMillis(int exploreTimeoutMillis) {
        this.exploreTimeoutMillis = exploreTimeoutMillis;
    }

    /**
     * Returns the success criteria for the verify gate.
     *
     * @return the list of success criteria; empty list means the verify rail is skipped
     */
    public List<String> getCriteria() {
        return criteria;
    }

    /**
     * Sets the success criteria for the verify gate.
     *
     * @param criteria the list of success criteria; null or empty means the verify rail is skipped
     */
    public void setCriteria(List<String> criteria) {
        this.criteria = criteria;
    }

    /**
     * Returns the maximum replan count for the shared replan budget.
     *
     * @return the maximum replan count
     */
    public int getMaxReplan() {
        return maxReplan;
    }

    /**
     * Returns whether the proactive convergence verifier is enabled.
     *
     * @return true if the proactive convergence verifier is enabled, false otherwise
     */
    public boolean isProactiveConvergenceEnabled() {
        return proactiveConvergenceEnabled;
    }

    /**
     * Sets whether the proactive convergence verifier is enabled.
     *
     * @param proactiveConvergenceEnabled true to enable the proactive convergence verifier, false to disable
     */
    public void setProactiveConvergenceEnabled(boolean proactiveConvergenceEnabled) {
        this.proactiveConvergenceEnabled = proactiveConvergenceEnabled;
    }

    /**
     * Returns the stall window for the proactive convergence verifier.
     *
     * @return the number of consecutive flatlined tool rounds before the verifier fires
     */
    public int getProactiveConvergenceStallWindow() {
        return proactiveConvergenceStallWindow;
    }

    /**
     * Sets the stall window for the proactive convergence verifier.
     *
     * @param proactiveConvergenceStallWindow the number of consecutive flatlined tool rounds before the verifier fires
     */
    public void setProactiveConvergenceStallWindow(int proactiveConvergenceStallWindow) {
        this.proactiveConvergenceStallWindow = proactiveConvergenceStallWindow;
    }

    /**
     * Sets the maximum replan count for the shared replan budget.
     *
     * @param maxReplan the maximum replan count
     */
    public void setMaxReplan(int maxReplan) {
        this.maxReplan = maxReplan;
    }

    /**
     * Build an ExploreBudget from the configured properties.
     *
     * @return a budget record
     */
    public ExploreBudget toExploreBudget() {
        return new ExploreBudget(exploreRounds, maxSubagents, exploreTimeoutMillis);
    }
}
