/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-invoke trace data collected by {@link TraceCollectingRail} during
 * {@code ReActAgent.invoke}, enriched post-invoke with the agent's result map.
 *
 * <p>Data from two sources:
 * <ul>
 *   <li><b>Runtime (TraceCollectingRail hooks)</b>: modelCallCount, toolCalls, toolExceptions</li>
 *   <li><b>Post-invoke (HillClimbingTuner reads result map)</b>: verified, degraded, criteriaResult,
 *       unmetCriteria, finalOutput</li>
 * </ul>
 *
 * <p>This separation avoids timing issues between rail priorities — the rail only
 * collects what it can observe unconditionally (counts), and the terminal verify
 * state is read from the agent's return value after {@code invoke()} returns.
 */
public class TraceFeedbackRecord {

    // -- Runtime-collected fields (from TraceCollectingRail hooks) --

    private int modelCallCount = 0;
    private final List<String> toolCalls = new ArrayList<>();
    private final List<String> toolExceptions = new ArrayList<>();

    // -- Post-invoke fields (from agent.invoke() return value) --

    private boolean forceFinished = false;
    private boolean verified = false;
    private boolean degraded = false;
    private String criteriaResult = "";       // "PASS", "FAIL", or ""
    private String finalOutput = "";
    private final List<String> unmetCriteria = new ArrayList<>();

    // ================================================================
    // Runtime-collected adders
    // ================================================================

    public void incrementModelCallCount() {
        modelCallCount++;
    }

    public void addToolCall(String name) {
        toolCalls.add(name);
    }

    public void addToolException(String name) {
        toolExceptions.add(name);
    }

    // ================================================================
    // Post-invoke setters
    // ================================================================

    public void setForceFinished(boolean forceFinished) {
        this.forceFinished = forceFinished;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public void setCriteriaResult(String criteriaResult) {
        this.criteriaResult = criteriaResult != null ? criteriaResult : "";
    }

    public void setFinalOutput(String finalOutput) {
        this.finalOutput = finalOutput != null ? finalOutput : "";
    }

    public void setUnmetCriteria(List<String> unmet) {
        this.unmetCriteria.clear();
        if (unmet != null) {
            this.unmetCriteria.addAll(unmet);
        }
    }

    // ================================================================
    // Shortcut queries
    // ================================================================

    /** True if the agent terminated with CRITERIA_RESULT = "PASS". */
    public boolean isPass() {
        return "PASS".equals(criteriaResult);
    }

    /** True if the agent terminated with CRITERIA_RESULT = "FAIL". */
    public boolean isFail() {
        return "FAIL".equals(criteriaResult);
    }

    /** True if this trace represents a useful signal (at least one model call made). */
    public boolean hasSignal() {
        return modelCallCount > 0;
    }

    /**
     * True if the trace suggests the agent struggled: degraded outcome AND
     * high model call count relative to a baseline. Used by {@link TraceAnalyzer}
     * to distinguish "barely tried" from "tried hard but failed".
     */
    public boolean isStruggled() {
        return degraded && modelCallCount >= 3;
    }

    // ================================================================
    // Getters
    // ================================================================

    public int getModelCallCount() {
        return modelCallCount;
    }

    public List<String> getToolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<String> getToolExceptions() {
        return List.copyOf(toolExceptions);
    }

    public boolean isForceFinished() {
        return forceFinished;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public String getCriteriaResult() {
        return criteriaResult;
    }

    public String getFinalOutput() {
        return finalOutput;
    }

    public List<String> getUnmetCriteria() {
        return List.copyOf(unmetCriteria);
    }
}
