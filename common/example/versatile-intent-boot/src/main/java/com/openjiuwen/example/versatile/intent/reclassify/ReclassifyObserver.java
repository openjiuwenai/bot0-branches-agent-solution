/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Objects;
import java.util.Optional;

/**
 * Decorates a {@link QueryStreamObserver} and intercepts the
 * {@code VERSATILE_INTENT_AMBIGUOUS} error payload emitted by the L2 adapter.
 *
 * <p>When the downstream observer's {@code onError} is called with a throwable
 * whose cause chain contains the ambiguous marker, this decorator swallows the
 * error (so the runtime does not surface it to the client) and stores the
 * parsed payload for the orchestrator to inspect after the stream terminates.
 * All other signals — {@code onNext}, {@code onComplete}, {@code isCancelled},
 * and non-ambiguous {@code onError} — are forwarded to the downstream observer
 * unchanged.
 *
 * @since 2026-07-24
 */
public final class ReclassifyObserver implements QueryStreamObserver {
    private final QueryStreamObserver downstream;
    private AmbiguousPayload ambiguousPayload;

    public ReclassifyObserver(QueryStreamObserver downstream) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
    }

    @Override
    public void onNext(QueryChunk chunk) {
        downstream.onNext(chunk);
    }

    @Override
    public void onError(Throwable error) {
        Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromThrowable(error);
        if (parsed.isPresent()) {
            ambiguousPayload = parsed.get();
            return;
        }
        downstream.onError(error);
    }

    @Override
    public void onComplete() {
        downstream.onComplete();
    }

    @Override
    public boolean isCancelled() {
        return downstream.isCancelled();
    }

    /**
     * Returns whether an ambiguous payload was intercepted during the stream.
     *
     * @return {@code true} if {@code onError} received an ambiguous payload
     */
    public boolean ambiguousTriggered() {
        return ambiguousPayload != null;
    }

    /**
     * Returns the intercepted ambiguous payload, if any.
     *
     * @return the parsed payload, or empty if no ambiguous error was intercepted
     */
    public Optional<AmbiguousPayload> ambiguousPayload() {
        return Optional.ofNullable(ambiguousPayload);
    }
}
