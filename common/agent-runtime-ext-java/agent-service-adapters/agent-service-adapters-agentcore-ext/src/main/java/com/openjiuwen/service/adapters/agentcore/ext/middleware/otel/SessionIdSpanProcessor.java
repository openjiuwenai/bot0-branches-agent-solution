/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.SessionContextHolder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Writes {@code session.id} onto every span at start time. The session is read from the
 * engine's {@link SessionContextHolder}, which is bound on the same thread before the
 * agent callbacks fire (ReAct/DeepAgent execution paths).
 *
 * @since 2026-08-07
 */
public final class SessionIdSpanProcessor implements SpanProcessor {

    private static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Session session = SessionContextHolder.getCurrentSession();
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }
        span.setAttribute(SESSION_ID, session.getSessionId());
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // session.id is written entirely at start time; nothing to do on end.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
