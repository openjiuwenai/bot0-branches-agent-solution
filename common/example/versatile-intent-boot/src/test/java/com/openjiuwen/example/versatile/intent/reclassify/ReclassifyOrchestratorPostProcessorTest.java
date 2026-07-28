/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.example.versatile.intent.routecache.RouteCache;
import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackHandler;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

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

    @Test
    void wrappingPreservesSecondaryInterfaceAndDelegates() {
        ReclassifyProperties properties = new ReclassifyProperties();
        properties.setEnabled(false);
        ReclassifyOrchestratorPostProcessor processor =
                new ReclassifyOrchestratorPostProcessor(properties, emptyProvider());

        A2AEnabledServeOrchestrator wrapped = mock(A2AEnabledServeOrchestrator.class);
        QueryResponse stubbedResponse = mock(QueryResponse.class);
        when(wrapped.query(any())).thenReturn(stubbedResponse);
        when(wrapped.onAccepted(any())).thenReturn(true);

        Object proxy = processor.postProcessAfterInitialization(wrapped, "a2aEnabledServeOrchestrator");

        // Issue #50 regression: the wrapped bean must still expose the
        // A2aPushNotificationCallbackHandler interface, otherwise injection
        // points expecting that type fail with BeanNotOfRequiredTypeException.
        assertThat(proxy).isInstanceOf(ServeOrchestrator.class);
        assertThat(proxy).isInstanceOf(A2aPushNotificationCallbackHandler.class);
        assertThat(proxy).isNotSameAs(wrapped);

        // ServeOrchestrator calls are routed through the reclassify decorator.
        assertThat(((ServeOrchestrator) proxy).query(mock(ServeRequest.class))).isSameAs(stubbedResponse);
        verify(wrapped).query(any());

        // Secondary-interface calls fall through to the wrapped bean unchanged.
        assertThat(((A2aPushNotificationCallbackHandler) proxy).onAccepted(null)).isTrue();
        verify(wrapped).onAccepted(any());
    }
}
