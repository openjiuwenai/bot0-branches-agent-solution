/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

/**
 * Parsed form of the {@code VERSATILE_INTENT_AMBIGUOUS} payload produced by
 * the L2 adapter when {@code intent_id} matches the configured ambiguous id
 * and no default workflow is configured.
 *
 * @param intentId           the {@code intent_id} value returned by L2 (typically "1")
 * @param responseContent    the L2 {@code response_content}, used to augment L1 context
 * @param ambiguousIntentId  the configured ambiguous intent id (typically "1")
 * @since 2026-07-24
 */
public record AmbiguousPayload(String intentId, String responseContent, String ambiguousIntentId) {
}
