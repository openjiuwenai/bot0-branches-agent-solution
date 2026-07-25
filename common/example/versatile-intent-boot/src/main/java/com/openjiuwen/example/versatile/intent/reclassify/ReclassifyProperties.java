/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the L1 reclassify decorator.
 *
 * <p>When {@code enabled=true}, the {@link ReclassifyServeOrchestrator} wraps
 * the runtime's {@code A2AEnabledServeOrchestrator} and retries the L1 intent
 * workflow with augmented context when L2 returns an answer envelope whose
 * {@code intent_id} matches {@link #getAmbiguousIntentId()}.
 *
 * @since 2026-07-24
 */
@ConfigurationProperties(prefix = "openjiuwen.service.versatile.intent-reclassify")
public class ReclassifyProperties {
    private boolean enabled = false;
    private int maxReclassify = 1;
    private String ambiguousIntentId = "1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxReclassify() {
        return maxReclassify;
    }

    public void setMaxReclassify(int maxReclassify) {
        this.maxReclassify = Math.max(0, maxReclassify);
    }

    public String getAmbiguousIntentId() {
        return ambiguousIntentId;
    }

    public void setAmbiguousIntentId(String ambiguousIntentId) {
        this.ambiguousIntentId = ambiguousIntentId == null || ambiguousIntentId.isBlank()
                ? "1" : ambiguousIntentId;
    }
}
