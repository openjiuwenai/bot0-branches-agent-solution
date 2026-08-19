/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

/**
 * A resolved handoff target. Transient per outbound call, never persisted.
 *
 * @since 2026-08-19
 */
public record ResolvedTarget(String agentId, ResolutionSource source) {

    public enum ResolutionSource {
        DIRECT, INTENT_MAPPING, DOMAIN_MAPPING
    }
}
