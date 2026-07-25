/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        observer = new ReclassifyObserver(downstream);
    }

    @Test
    void forwardsOnNextAndOnComplete() {
        QueryChunk chunk = new QueryChunk();
        observer.onNext(chunk);
        observer.onComplete();

        verify(downstream).onNext(chunk);
        verify(downstream).onComplete();
    }

    @Test
    void interceptsAmbiguousOnError() {
        // Build a throwable whose message contains the ambiguous marker inside
        // a JSON object — same shape the L2 adapter emits via TYPE_ERROR.
        String payload = "{\"code\":\"VERSATILE_INTENT_AMBIGUOUS\","
                + "\"intent_id\":\"1\","
                + "\"response_content\":\"not sure\","
                + "\"ambiguous_intent_id\":\"1\"}";
        Throwable error = new RuntimeException(payload);

        observer.onError(error);

        // Downstream must NOT receive the error.
        verifyNoInteractions(downstream);
        assertThat(observer.ambiguousTriggered()).isTrue();
        Optional<AmbiguousPayload> parsed = observer.ambiguousPayload();
        assertThat(parsed).isPresent();
        assertThat(parsed.get().intentId()).isEqualTo("1");
        assertThat(parsed.get().responseContent()).isEqualTo("not sure");
        assertThat(parsed.get().ambiguousIntentId()).isEqualTo("1");
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
    void interceptsAmbiguousPayloadInWrappedCauseChain() {
        // L2 ambiguous errors arrive wrapped by remote-agent runtime exceptions;
        // the parser walks the cause chain, and so must the observer.
        String payload = "{\"code\":\"VERSATILE_INTENT_AMBIGUOUS\","
                + "\"intent_id\":\"1\","
                + "\"response_content\":\"unsure\","
                + "\"ambiguous_intent_id\":\"1\"}";
        Throwable cause = new RuntimeException(payload);
        Throwable wrapped = new RuntimeException("remote agent failed", cause);

        observer.onError(wrapped);

        verifyNoInteractions(downstream);
        assertThat(observer.ambiguousTriggered()).isTrue();
        assertThat(observer.ambiguousPayload()).isPresent();
        assertThat(observer.ambiguousPayload().get().responseContent()).isEqualTo("unsure");
    }

    @Test
    void rejectsNullDownstreamAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReclassifyObserver(null))
                .withMessageContaining("downstream");
    }
}
