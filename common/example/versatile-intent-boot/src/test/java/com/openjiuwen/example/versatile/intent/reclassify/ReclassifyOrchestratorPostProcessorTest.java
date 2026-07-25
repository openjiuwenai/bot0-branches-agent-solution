/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ReclassifyOrchestratorPostProcessorTest {
    @Test
    void doesNotWrapNonOrchestratorBean() {
        ReclassifyProperties properties = new ReclassifyProperties();
        properties.setEnabled(true);
        ReclassifyOrchestratorPostProcessor processor = new ReclassifyOrchestratorPostProcessor(properties);

        Object other = new Object();
        assertThat(processor.postProcessAfterInitialization(other, "otherBean")).isSameAs(other);
    }

    @Test
    void rejectsNullPropertiesAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReclassifyOrchestratorPostProcessor(null))
                .withMessageContaining("properties");
    }
}
