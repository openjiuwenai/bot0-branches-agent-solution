/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies defensive request context copies.
 *
 * @since 0.1.0
 */
class CustomRestProtocolAdapterTest {
    @Test
    void contextDefensivelyCopiesTopLevelCollectionsAndValues() {
        List<String> headerValues = new ArrayList<>(List.of("one"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("x-test", headerValues);
        Map<String, String> path = new LinkedHashMap<>(Map.of("id", "42"));
        Map<String, List<String>> query = new LinkedHashMap<>(Map.of("q", List.of("a", "b")));
        Map<String, Object> body = new LinkedHashMap<>(Map.of("nested", Map.of("value", 1)));

        var context = new CustomRestProtocolAdapter.Context(headers, path, query, body);
        headers.clear();
        headerValues.add("two");
        path.clear();
        query.clear();
        body.clear();

        assertThat(context.headers()).containsEntry("x-test", List.of("one"));
        assertThat(context.pathVariables()).containsEntry("id", "42");
        assertThat(context.queryParams()).containsEntry("q", List.of("a", "b"));
        assertThat(context.body()).containsKey("nested");
        assertThatThrownBy(() -> context.headers().put("other", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
