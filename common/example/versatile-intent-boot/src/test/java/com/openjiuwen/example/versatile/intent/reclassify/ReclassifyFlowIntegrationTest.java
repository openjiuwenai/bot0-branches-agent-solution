/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration-style test: full L1 decorator loop around a mocked L1
 * orchestrator, covering reclassify success, reclassify limit, and
 * augmented-context propagation.
 *
 * <p>Signal path (new design): the wrapped orchestrator surfaces L2 ambiguous
 * as a {@code QueryResponse} whose {@code content} is the envelope JSON
 * (sync) or a {@code TYPE_CHUNK} envelope (stream). The decorator inspects
 * the response/chunk and re-invokes with augmented context.
 */
class ReclassifyFlowIntegrationTest {
    private static final String AMBIGUOUS_ENVELOPE_JSON = "{\"type\":\"answer\","
            + "\"intent_id\":\"1\","
            + "\"payload\":{\"content\":\"无法确定国内/国际\"}}";

    private static final Map<String, Object> AMBIGUOUS_ENVELOPE_MAP = new LinkedHashMap<>(
            Map.of("type", "answer", "intent_id", "1",
                    "response_content", "无法确定国内/国际", "ambiguous", true));

    private ServeOrchestrator wrapped;
    private ReclassifyProperties properties;

    @BeforeEach
    void setUp() {
        wrapped = mock(ServeOrchestrator.class);
        properties = new ReclassifyProperties();
        properties.setEnabled(true);
        properties.setMaxReclassify(1);
        properties.setAmbiguousIntentId("1");
    }

    private ServeRequest sampleRequest() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("conv-1");
        request.setUserId("user-1");
        request.setStream(true);
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "订一张北京到上海的机票");
        messages.add(userMsg);
        request.setMessages(messages);
        return request;
    }

    private QueryResponse ambiguousResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", AMBIGUOUS_ENVELOPE_JSON);
        return new QueryResponse(result, "conv-1");
    }

    @Test
    void queryReclassifySuccessPathUsesAugmentedContext() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        when(wrapped.query(any())).thenReturn(ambiguousResponse())
                .thenReturn(new QueryResponse(Map.of("content", "酒店预订成功"), "conv-1"));

        QueryResponse response = decorator.query(sampleRequest());

        assertThat(response.getResult()).isEqualTo(Map.of("content", "酒店预订成功"));
        verify(wrapped, times(2)).query(any());
    }

    @Test
    void queryReclassifyLimitWhenSecondAttemptAlsoAmbiguous() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        when(wrapped.query(any())).thenReturn(ambiguousResponse());

        assertThatThrownBy(() -> decorator.query(sampleRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERSATILE_INTENT_RECLASSIFY_LIMIT");
        verify(wrapped, times(2)).query(any());
    }

    @Test
    void streamReclassifySuccessEmitsFinalAnswer() {
        ReclassifyServeOrchestrator decorator = new ReclassifyServeOrchestrator(wrapped, properties);
        QueryStreamObserver downstream = mock(QueryStreamObserver.class);

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, AMBIGUOUS_ENVELOPE_MAP));
            obs.onComplete();
            return null;
        }).doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "酒店预订成功"));
            obs.onComplete();
            return null;
        }).when(wrapped).streamQuery(any(), any());

        decorator.streamQuery(sampleRequest(), downstream);

        verify(downstream, times(1)).onComplete();
        verify(downstream, times(0)).onError(any());
    }
}
