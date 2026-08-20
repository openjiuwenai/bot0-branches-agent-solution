/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.spi;

import com.openjiuwen.agents.intent.exception.IntentInitializationException;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;

/**
 * SPI for building a complete initialized intent catalog from one catalog version.
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
