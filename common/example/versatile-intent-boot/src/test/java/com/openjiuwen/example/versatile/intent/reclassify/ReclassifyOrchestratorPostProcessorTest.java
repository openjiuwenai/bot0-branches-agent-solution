/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

import com.openjiuwen.example.versatile.intent.routecache.RouteCache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Verifies {@link ReclassifyOrchestratorPostProcessor} bean-replacement
 * semantics and constructor null-checks.
 *
 * @since 2026-07-24
 */
class ReclassifyOrchestratorPostProcessorTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<RouteCache> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    @Test
    void doesNotWrapNonOrchestratorBean() {
        ReclassifyProperties properties = new ReclassifyProperties();
        properties.setEnabled(true);
        ReclassifyOrchestratorPostProcessor processor =
                new ReclassifyOrchestratorPostProcessor(properties, emptyProvider());

        Object other = new Object();
        assertThat(processor.postProcessAfterInitialization(other, "otherBean")).isSameAs(other);
    }

    @Test
    void rejectsNullPropertiesAtConstruction() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ReclassifyOrchestratorPostProcessor(null, emptyProvider()))
                .withMessageContaining("properties");
    }

    @Test
    void rejectsNullRouteCacheProviderAtConstruction() {
        ReclassifyProperties properties = new ReclassifyProperties();
        assertThatNullPointerException()
                .isThrownBy(() -> new ReclassifyOrchestratorPostProcessor(properties, null))
                .withMessageContaining("routeCacheProvider");
    }
}
