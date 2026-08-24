/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

/** Signals a deterministic FEAT-017 projection wire-contract failure. */
final class ProjectionProtocolException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ProjectionProtocolException(String message) {
        super("PROJECTION_PAYLOAD_INVALID: " + message);
    }

    ProjectionProtocolException(String message, Throwable cause) {
        super("PROJECTION_PAYLOAD_INVALID: " + message, cause);
    }
}
