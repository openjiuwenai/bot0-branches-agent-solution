/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Selects the delivery path (FEAT-012 §0.2). Deployment-level fixed by
 * {@code gateway.path-mode} (default {@code direct}); not per-call, not
 * client-visible. The facade consults this to dispatch DIRECT (FEAT-011) vs
 * BUS (FEAT-012).
 *
 * @since 2026-07-24
 */
@Component
public class PathSelector {
    private final PathMode mode;

    /**
     * Construct from the deployment config.
     *
     * @param mode raw {@code gateway.path-mode} value (default {@code direct})
     */
    public PathSelector(@Value("${gateway.path-mode:direct}") String mode) {
        this.mode = PathMode.fromConfig(mode);
    }

    /**
     * @return the configured path mode
     */
    public PathMode mode() {
        return mode;
    }

    /**
     * @return {@code true} if the BUS path is active
     */
    public boolean isBus() {
        return mode == PathMode.BUS;
    }

    /**
     * @return {@code true} if the DIRECT path is active
     */
    public boolean isDirect() {
        return mode == PathMode.DIRECT;
    }
}
