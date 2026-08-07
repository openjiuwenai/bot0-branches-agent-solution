/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;

/**
 * Builds a complete initialized intent directory from one catalog version.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface IntentInitializer {
    /**
     * Initializes the complete directory.
     *
     * @param config static suite configuration
     * @param catalog complete catalog input
     * @return initialized directory
     * @throws IntentInitializationException when initialization fails
     */
    InitializedIntents initialize(IntentSuiteConfig config, IntentCatalogInput catalog)
            throws IntentInitializationException;
}
