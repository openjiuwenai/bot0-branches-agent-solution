/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.obs;

/**
 * Sink for {@link AuditEvent}s (FEAT-011 L2 §3.7 — "结构化日志或等价审计存储").
 * 730 default impl is structured logging; tests capture events with a fake.
 *
 * @since 0.1.0
 */
public interface AuditSink {
    /**
     * Record one governance audit event. Must not throw on the main path
     * (L2 §3.7 P4 — audit failure does not block an already-passed request).
     *
     * @param event the audit event
     */
    void record(AuditEvent event);
}
