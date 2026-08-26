/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * W3C {@code traceparent} header parser shared by the ingress filters of both otel
 * batches. Extracted from the batch-1 {@code HttpRequestSpanFilter} (private static
 * {@code extractParent}) so the batch-2 trace identity filter reuses the same parsing
 * discipline instead of duplicating it.
 *
 * @since 2026-08-26
 */
public final class W3cTraceContextParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(W3cTraceContextParser.class);

    private static final int PART_COUNT = 4;
    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;

    private W3cTraceContextParser() {
    }

    /**
     * Parses the header into an OTel parent context (remote parent span context).
     * Returns the root context when the header is absent or malformed.
     *
     * @param traceparent W3C traceparent header value, may be null
     * @return parent context (root when not parseable)
     */
    public static Context parseToContext(String traceparent) {
        Optional<String[]> parts = split(traceparent);
        if (parts.isEmpty()) {
            return Context.root();
        }
        String[] segments = parts.get();
        try {
            return Context.root().with(Span.wrap(SpanContext.createFromRemoteParent(
                    segments[1], segments[2], TraceFlags.fromHex(segments[3], 0), TraceState.getDefault())));
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.warn("illegal traceparent ignored: {}", e.getClass().getSimpleName());
            return Context.root();
        }
    }

    /**
     * Extracts the trace id from the header with format validation (32 lowercase hex).
     *
     * @param traceparent W3C traceparent header value, may be null
     * @return trace id, or empty when absent/malformed
     */
    public static Optional<String> parseTraceId(String traceparent) {
        return split(traceparent)
                .filter(parts -> isLowerHex(parts[1], TRACE_ID_LENGTH) && isLowerHex(parts[2], SPAN_ID_LENGTH))
                .map(parts -> parts[1]);
    }

    private static Optional<String[]> split(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        String[] parts = traceparent.trim().split("-");
        return parts.length == PART_COUNT ? Optional.of(parts) : Optional.empty();
    }

    private static boolean isLowerHex(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
