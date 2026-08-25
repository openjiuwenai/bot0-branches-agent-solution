/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.deepagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.model.FinishAction;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentDecisionStatus;
import com.openjiuwen.agents.intent.model.ReturnAction;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** Tests the model-visible JSON encoding of terminal intent decisions. */
class IntentToolResultCodecTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IntentToolResultCodec codec = new IntentToolResultCodec();

    @Test
    void encodesReturnFallbackUnmatchedAndFailedDecisions() throws Exception {
        Map<String, Object> matched = decode(codec.encode(new IntentDecision(IntentDecisionStatus.MATCHED,
                "local-answer", new ReturnAction(Map.of("notice", "直接返回的业务结果")), null)));
        assertThat(matched).containsEntry("status", "MATCHED").containsEntry("intentId", "local-answer")
                .containsEntry("result", Map.of("notice", "直接返回的业务结果")).doesNotContainKey("message");

        Map<String, Object> fallback = decode(codec.encode(new IntentDecision(IntentDecisionStatus.FALLBACK,
                "default-fallback", new ReturnAction("请重新描述"), null)));
        assertThat(fallback).containsEntry("status", "FALLBACK").containsEntry("intentId", "default-fallback")
                .containsEntry("result", "请重新描述");

        Map<String, Object> finish = decode(codec.encode(new IntentDecision(IntentDecisionStatus.FALLBACK,
                "terminal-fallback", new FinishAction(Map.of("notice", "超出范围"), "超出范围，请补充说明。"), null)));
        assertThat(finish).containsEntry("status", "FALLBACK").containsEntry("intentId", "terminal-fallback")
                .containsEntry("result", Map.of("notice", "超出范围")).doesNotContainKey("output");

        Map<String, Object> unmatched = decode(
                codec.encode(new IntentDecision(IntentDecisionStatus.UNMATCHED, null, null, "no intent matched")));
        assertThat(unmatched).containsEntry("status", "UNMATCHED").containsEntry("message", "no intent matched")
                .doesNotContainKey("intentId").doesNotContainKey("result");

        Map<String, Object> failed = decode(codec.encode(
                new IntentDecision(IntentDecisionStatus.FAILED, "broken", null, "intent result function failed")));
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("intentId", "broken")
                .containsEntry("message", "intent result function failed").doesNotContainKey("result");
    }

    @Test
    void rejectsResultsThatAreNotJsonSerializable() {
        IntentDecision decision = new IntentDecision(IntentDecisionStatus.MATCHED, "opaque",
                new ReturnAction(new Object()), null);
        assertThatThrownBy(() -> codec.encode(decision)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> decode(String json) throws Exception {
        return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }
}
