/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.obs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 730 default {@link AuditSink}: structured SLF4J logging. Records only the
 * minimal fields — never the Bearer token or the prompt/body text (T-G5-3).
 *
 * @since 0.1.0
 */
@Component
public class LoggingAuditSink implements AuditSink {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingAuditSink.class);

    @Override
    public void record(AuditEvent event) {
        LOG.info(
                "governance audit traceId={} principalId={} tenantId={} method={} outcome={} rejectStage={} code={}",
                event.traceId(), event.principalId(), event.tenantId(), event.method(),
                event.outcome(), event.rejectStage(), event.code());
    }
}
