/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

/**
 * Structured control information extracted from a controller intent-handoff message.
 * The handoff message is control information only — it never substitutes the user
 * message (spec 2.3). Transient per classification, never persisted.
 *
 * @param handoffType handoff type marker (e.g. L1-to-L2 / L2-back-to-L1), nullable
 * @param intentId intent identifier, nullable
 * @param businessDomain business domain, nullable
 * @param targetAgentId target agent when the controller names it directly, nullable
 * @param dedupKey controller-provided dedup key, nullable (executor derives a hash)
 * @param raw raw payload for diagnostics only (masked per DFX-001), never exposed northbound
 * @since 2026-08-19
 */
public record IntentHandoff(String handoffType, String intentId, String businessDomain,
        String targetAgentId, String dedupKey, String raw) {
}
