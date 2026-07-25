/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReclassifyObserverTest {

    private QueryStreamObserver downstream;
    private ReclassifyObserver observer;

    @BeforeEach
    void setUp() {
        downstream = mock(QueryStreamObserver.class);
        observer = new ReclassifyObserver(downstream, "1");
    }

    @Test
    void forwardsNonAmbiguousOnNextAndOnComplete() {
        QueryChunk chunk = new QueryChunk();
        observer.onNext(chunk);
        observer.onComplete();

        verify(downstream).onNext(chunk);
        verify(downstream).onComplete();
    }

    @Test
    void interceptsAmbiguousOnNextFromMapEnvelope() {
        // L2 adapter emits TYPE_CHUNK with a Map envelope carrying intent_id.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("intent_id", "1");
        envelope.put("response_content", "not sure");
        envelope.put("ambiguous", true);
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, envelope);

        observer.onNext(chunk);

        verifyNoInteractions(downstream);
        assertThat(observer.ambiguousTriggered()).isTrue();
        Optional<AmbiguousPayload> parsed = observer.ambiguousPayload();
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
        assertThat(parsed.get().responseContent()).isEqualTo("not sure");
        assertThat(parsed.get().ambiguousIntentId()).isEqualTo("1");
    }

    @Test
    void interceptsAmbiguousOnNextFromStringEnvelope() {
        // Gateway caller may surface the envelope as a JSON string.
        String payload = "{\"type\":\"answer\",\"intent_id\":\"1\","
                + "\"payload\":{\"content\":\"unsure\"}}";
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, payload);

        observer.onNext(chunk);

        verifyNoInteractions(downstream);
        assertThat(observer.ambiguousTriggered()).isTrue();
        assertThat(observer.ambiguousPayload()).get()
                .extracting(AmbiguousPayload::responseContent)
                .isEqualTo("unsure");
    }

    @Test
    void forwardsNonAmbiguousOnError() {
        Throwable error = new RuntimeException("some unrelated failure");
        observer.onError(error);

        verify(downstream).onError(error);
        assertThat(observer.ambiguousTriggered()).isFalse();
        assertThat(observer.ambiguousPayload()).isEmpty();
    }

    @Test
    void delegatesIsCancelledToDownstream() {
        when(downstream.isCancelled()).thenReturn(true);
        assertThat(observer.isCancelled()).isTrue();

        when(downstream.isCancelled()).thenReturn(false);
        assertThat(observer.isCancelled()).isFalse();
    }

    @Test
    void nonMatchingIntentIdDoesNotTrigger() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("intent_id", "intent_L2_hotel_domestic");
        envelope.put("response_content", "国内酒店");
        QueryChunk chunk = new QueryChunk(QueryChunk.TYPE_CHUNK, envelope);

        observer.onNext(chunk);

        verify(downstream).onNext(chunk);
        assertThat(observer.ambiguousTriggered()).isFalse();
    }

    @Test
    void rejectsNullDownstreamAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReclassifyObserver(null, "1"))
                .withMessageContaining("downstream");
    }
}
