/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight rail that captures the user's initial query into a shared
 * {@link AtomicReference}, so {@link com.openjiuwen.agents.edpa.explore.ExploreTool}
 * can access it when the LLM calls the explore tool.
 *
 * <p><b>承重缺陷 D 治本</b> (GEPA 综合裁判识别): ExploreTool.invoke needs
 * userInput, but in tool-driven mode (Species E) ExploreRail is not registered.
 * Without this capture rail, the AtomicReference stays null → ExploreTool
 * degrades to "no_input" → tool-driven explore is fake.
 *
 * <p>This rail fires in {@link #beforeModelCall} (priority 95, fires before
 * ExploreRail@90 and PreCompletionChecklistRail@80). It scans the message list
 * for the first {@link UserMessage} and writes its content into the shared
 * AtomicReference. The ExploreTool reads via {@code atomicRef::get} (a
 * Supplier<String> method reference).
 *
 * <p>Zero pushSteering, zero requestForceFinish — pure observation, no
 * control-flow side effects. This rail does NOT steer the loop.
 *
 * @since 2026-07
 */
public class UserInputCaptureRail extends AgentRail {
    /** Priority 95 — fires before ExploreRail(90) and PreCompletionChecklistRail(80). */
    private static final int PRIORITY = 95;

    private final AtomicReference<String> userInputRef;

    private boolean captured = false;

    /**
     * Constructs the capture rail with a shared AtomicReference.
     *
     * @param userInputRef shared mutable reference (written by this rail, read by ExploreTool)
     */
    public UserInputCaptureRail(AtomicReference<String> userInputRef) {
        if (userInputRef == null) {
            throw new IllegalArgumentException("userInputRef must not be null");
        }
        this.userInputRef = userInputRef;
        setPriority(PRIORITY);
    }

    /**
     * Captures the first UserMessage content into the AtomicReference.
     *
     * <p>Runs once — after the first successful capture, subsequent calls are
     * no-ops (the query doesn't change mid-conversation).
     *
     * @param ctx callback context for the pending model call; if {@code null}
     *            or lacking messages this is a silent no-op
     * @throws IllegalArgumentException never thrown; null inputs are tolerated
     */
    @Override
    public synchronized void beforeModelCall(AgentCallbackContext ctx) {
        if (captured) {
            return;
        }
        if (ctx.getContext() == null) {
            return;
        }
        List<BaseMessage> messages = ctx.getContext().getMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (BaseMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                String content = um.getContentAsString();
                if (content != null && !content.isEmpty()) {
                    userInputRef.set(content);
                    captured = true;
                    return;
                }
            }
        }
    }

    /**
     * Reports whether the user's initial query has been captured into the
     * shared {@link AtomicReference} by {@link #beforeModelCall}.
     *
     * @return true if the user input has been captured
     */
    public synchronized boolean isCaptured() {
        return captured;
    }
}
