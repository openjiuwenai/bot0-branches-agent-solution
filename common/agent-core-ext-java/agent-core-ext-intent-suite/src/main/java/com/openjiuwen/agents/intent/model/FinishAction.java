/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

/**
 * Synchronous result that also ends the calling Agent turn.
 *
 * <p>
 * A result function returns this action when {@code output} is already the answer meant for the
 * user, so letting the model paraphrase it can only lose or distort it. A refusal outside the
 * configured scope is the typical case; a compliance rejection or a closed-for-business reply
 * behaves the same way.
 *
 * <p>
 * {@code result} stays model-visible and is written to the Tool message exactly like
 * {@link ReturnAction#result()}, so the transcript and any checkpoint keep the structured
 * evidence. {@code output} is what the caller delivers instead of another model turn.
 *
 * @apiNote Ending the turn also abandons whatever the calling Agent had planned next. An intent
 *     that is one step of a larger plan should return {@link ReturnAction} instead, so the plan
 *     survives its result.
 *
 * @param result model-visible result, written to the Tool message
 * @param output user-facing answer that ends the turn, must not be blank
 *
 * @since 0.1.0
 */
public record FinishAction(Object result, String output) implements IntentAction {
}
