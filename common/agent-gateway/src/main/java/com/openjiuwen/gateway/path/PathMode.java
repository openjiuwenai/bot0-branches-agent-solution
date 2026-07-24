/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.path;

/**
 * Delivery path for client invocations (FEAT-012 §0.2). Deployment-level fixed
 * via {@code gateway.path-mode}; not per-call, not client-visible.
 * <ul>
 *   <li>{@code DIRECT} — FEAT-011 experience-plane main path (HTTP/SSE).</li>
 *   <li>{@code BUS} — FEAT-012 control-plane side path (control event via Event
 *       Bus; sync folds projections to five states, streaming needs STREAM_READY
 *       before point-to-point SSE).</li>
 * </ul>
 *
 * @since 2026-07-24
 */
public enum PathMode {
    DIRECT,
    BUS;

    /**
     * Parse a config value; blank → {@link #DIRECT} (lenient default). A non-blank
     * unknown value fails fast (typo in deployment config must not silently pick
     * the wrong delivery plane).
     *
     * @param config raw config value
     * @return the parsed path mode
     */
    public static PathMode fromConfig(String config) {
        if (config == null || config.isBlank()) {
            return DIRECT;
        }
        return switch (config.trim().toLowerCase()) {
            case "direct" -> DIRECT;
            case "bus" -> BUS;
            default -> throw new IllegalArgumentException(
                    "Unknown gateway.path-mode: '" + config + "' (expected direct|bus)");
        };
    }
}
