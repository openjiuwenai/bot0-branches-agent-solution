/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Objects;
import java.util.Optional;

/**
 * Decorates a {@link QueryStreamObserver} and intercepts the ambiguous answer
 * envelope emitted by the L2 adapter as a {@code TYPE_CHUNK}.
 *
 * <p>When {@code onNext} receives a {@code TYPE_CHUNK} whose data is an answer
 * envelope with {@code intent_id} matching the configured ambiguous id, this
 * decorator swallows the chunk (so the runtime does not surface it to the
 * client) and stores the parsed payload for the orchestrator to inspect after
 * the stream terminates. All other signals — non-ambiguous {@code onNext},
 * {@code onComplete}, {@code onError}, {@code isCancelled} — are forwarded to
 * the downstream observer unchanged.
 *
 * @since 2026-07-24
 */
public final class ReclassifyObserver implements QueryStreamObserver {
    private final QueryStreamObserver downstream;
    private final String ambiguousIntentId;
    private AmbiguousPayload ambiguousPayload;

    public ReclassifyObserver(QueryStreamObserver downstream, String ambiguousIntentId) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.ambiguousIntentId = ambiguousIntentId == null || ambiguousIntentId.isBlank() ? "1" : ambiguousIntentId;
    }

    @Override
    public void onNext(QueryChunk chunk) {
        if (QueryChunk.TYPE_CHUNK.equals(chunk.getType())) {
            Optional<AmbiguousPayload> parsed = AmbiguousPayloadParser.fromChunkData(
                    chunk.getData(), ambiguousIntentId);
            if (parsed.isPresent()) {
                ambiguousPayload = parsed.get();
                return;
            }
        }
        downstream.onNext(chunk);
    }

    @Override
    public void onError(Throwable error) {
        downstream.onError(error);
    }

    @Override
    public void onComplete() {
        if (ambiguousPayload != null) {
            // Ambiguous intercepted — swallow stream end so the decorator can
            // re-invoke the wrapped orchestrator with augmented context.
            return;
        }
        downstream.onComplete();
    }

    @Override
    public boolean isCancelled() {
        return downstream.isCancelled();
    }

    /**
     * Returns whether an ambiguous payload was intercepted during the stream.
     *
     * @return {@code true} if a {@code TYPE_CHUNK} ambiguous envelope was intercepted
     */
    public boolean ambiguousTriggered() {
        return ambiguousPayload != null;
    }

    /**
     * Returns the intercepted ambiguous payload, if any.
     *
     * @return the parsed payload, or empty if no ambiguous envelope was intercepted
     */
    public Optional<AmbiguousPayload> ambiguousPayload() {
        return Optional.ofNullable(ambiguousPayload);
    }
}
