/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

/**
 * Raised when route resolution fails — RDC returns an error, no endpoint, or the
 * HTTP call fails. The routing layer maps this to the S5 "no available route"
 * failure path (FEAT-011 L2 §7), never to a fabricated success.
 *
 * @since 0.1.0
 */
public class RouteResolutionException extends RuntimeException {
    /**
     * Construct.
     *
     * @param message failure description
     */
    public RouteResolutionException(String message) {
        super(message);
    }

    /**
     * Construct.
     *
     * @param message failure description
     * @param cause   underlying cause
     */
    public RouteResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
