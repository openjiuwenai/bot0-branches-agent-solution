/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * W3cTraceContextParser 的单元测试：合法 header 解析 traceId、畸形/缺失容错。
 */
class W3cTraceContextParserTest {
    private static final String TRACE_ID = "1cd0e9b4ebc1bc708de6aae571b089ce";
    private static final String SPAN_ID = "505c131920042a38";

    @Test
    void parsesValidHeader() {
        assertThat(W3cTraceContextParser.parseTraceId("00-" + TRACE_ID + "-" + SPAN_ID + "-01"))
                .contains(TRACE_ID);
    }

    @Test
    void emptyForMissingOrMalformed() {
        assertThat(W3cTraceContextParser.parseTraceId(null)).isEmpty();
        assertThat(W3cTraceContextParser.parseTraceId("")).isEmpty();
        assertThat(W3cTraceContextParser.parseTraceId("00-abc-def-01")).isEmpty();
        assertThat(W3cTraceContextParser.parseTraceId("00-" + TRACE_ID.toUpperCase() + "-" + SPAN_ID + "-01"))
                .isEmpty();
        assertThat(W3cTraceContextParser.parseTraceId("00-zz0e9b4ebc1bc708de6aae571b089ce-" + SPAN_ID + "-01"))
                .isEmpty();
    }

    @Test
    void contextParsingToleratesGarbage() {
        assertThat(W3cTraceContextParser.parseToContext(null)).isNotNull();
        assertThat(W3cTraceContextParser.parseToContext("garbage")).isNotNull();
    }
}
