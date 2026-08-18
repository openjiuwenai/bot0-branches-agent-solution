/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.opentelemetry.api.trace.Span;

import org.junit.jupiter.api.Test;

/**
 * OtelRailBinding 按请求绑定的单元测试。
 */
class OtelRailBindingTest {
    @Test
    void bind_nullConversationId_returnsDisabledNoop() {
        OtelRailBinding binding = OtelRailBinding.bind(new Object(), null, null);
        assertThat(binding.isEnabled()).isFalse();
        assertThatCode(binding::close).doesNotThrowAnyException();
    }

    @Test
    void bind_blankConversationId_returnsDisabledNoop() {
        OtelRailBinding binding = OtelRailBinding.bind(new Object(), "  ", null);
        assertThat(binding.isEnabled()).isFalse();
    }

    @Test
    void bind_nullAgent_returnsDisabledNoop() {
        OtelRailBinding binding = OtelRailBinding.bind(null, "conv-1", null);
        assertThat(binding.isEnabled()).isFalse();
        assertThatCode(binding::close).doesNotThrowAnyException();
    }

    @Test
    void close_isIdempotent() {
        OtelRailBinding binding = OtelRailBinding.bind(null, "conv-1", Span.getInvalid());
        binding.close();
        assertThatCode(binding::close).doesNotThrowAnyException();
    }
}
