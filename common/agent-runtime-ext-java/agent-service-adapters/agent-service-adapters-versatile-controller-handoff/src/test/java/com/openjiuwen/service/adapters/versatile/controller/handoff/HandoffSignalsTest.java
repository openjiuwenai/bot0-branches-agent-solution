/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * HandoffSignals 信封识别验收：纯信封回归 + 流式场景下信封与前置泄漏帧拼接
 * （含 JSON 帧拼接与纯文本前缀）仍须识别（FEAT-002 生产回归，2026-08-24）。
 *
 * @since 2026-08-24
 */
class HandoffSignalsTest {
    private static final String ENVELOPE = HandoffSignals.notInScopeEnvelope(
            new IntentHandoff("不在范围", "不在范围", null, null, null, "{}"));

    @Test
    void detectsBareEnvelope() {
        assertThat(HandoffSignals.isNotInScope(ENVELOPE)).isTrue();
    }

    @Test
    void detectsEnvelopeAfterLeakedJsonFrame() {
        // 流式 re-invoke：答案帧透传泄漏后与信封背靠背拼接（extractTaskResult 无
        // terminal 标记时的拼接行为）
        String leakedFrame = "{\"event\":\"message\",\"data\":{\"text\":\"前置业务输出：部分答案\","
                + "\"node_name\":\"前置输出\",\"node_type\":\"Q\"}}";
        assertThat(HandoffSignals.isNotInScope(leakedFrame + ENVELOPE)).isTrue();
    }

    @Test
    void detectsEnvelopeAfterPlainTextPrefix() {
        // 非 JSON 文本前缀：sequence 解析失败，退回定界子串匹配
        assertThat(HandoffSignals.isNotInScope("前置业务输出：部分答案" + ENVELOPE)).isTrue();
    }

    @Test
    void rejectsOrdinaryAnswerAndBlank() {
        assertThat(HandoffSignals.isNotInScope("{\"type\":\"answer\",\"output\":\"本域业务答案\"}")).isFalse();
        assertThat(HandoffSignals.isNotInScope("")).isFalse();
        assertThat(HandoffSignals.isNotInScope(null)).isFalse();
        assertThat(HandoffSignals.isNotInScope("普通文本答案，无信封")).isFalse();
    }
}
