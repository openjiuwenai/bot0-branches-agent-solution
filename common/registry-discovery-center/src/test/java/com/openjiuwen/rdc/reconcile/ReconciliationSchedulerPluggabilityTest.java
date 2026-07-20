/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEventConsumer;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Unit coverage for pluggable {@link DeploymentDiscoveryProvider} wiring in
 * {@link ReconciliationScheduler} (injected beans + optional static yml provider).
 *
 * @since 0.1.0 (2026)
 */
class ReconciliationSchedulerPluggabilityTest {
    @Test
    void registers_injected_provider_and_subscribes_watch() {
        RecordingProvider dynamic = new RecordingProvider("dynamic-src");
        DeploymentDiscoveryProperties props = emptyProperties();

        ReconciliationScheduler scheduler = newScheduler(props, providerOf(dynamic));

        assertThat(scheduler.registeredProviders())
                .extracting(DeploymentDiscoveryProvider::sourceId)
                .containsExactly("dynamic-src");
        assertThat(dynamic.watchSubscribed.get()).isTrue();
    }

    @Test
    void combines_injected_provider_with_static_yml_instances() {
        RecordingProvider dynamic = new RecordingProvider("dynamic-src");
        DeploymentDiscoveryProperties props = emptyProperties();
        DeploymentDiscoveryProperties.StaticInstanceEntry entry =
                new DeploymentDiscoveryProperties.StaticInstanceEntry();
        entry.setTenantId("tenant-A");
        entry.setServiceId("billing-svc");
        entry.setInstanceId("billing-svc-pod-0");
        entry.setBaseUrl("http://localhost:8090");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        props.setInstances(List.of(entry));

        ReconciliationScheduler scheduler = newScheduler(props, providerOf(dynamic));

        assertThat(scheduler.registeredProviders())
                .extracting(DeploymentDiscoveryProvider::sourceId)
                .containsExactly("dynamic-src", StaticDeploymentDiscoveryProvider.SOURCE_ID);
        assertThat(dynamic.watchSubscribed.get()).isTrue();
    }

    @Test
    void skips_injected_static_provider_bean_to_avoid_duplicate() {
        StaticDeploymentDiscoveryProvider injectedStatic =
                new StaticDeploymentDiscoveryProvider(List.of());
        DeploymentDiscoveryProperties props = emptyProperties();
        DeploymentDiscoveryProperties.StaticInstanceEntry entry =
                new DeploymentDiscoveryProperties.StaticInstanceEntry();
        entry.setTenantId("tenant-A");
        entry.setServiceId("billing-svc");
        entry.setInstanceId("pod-0");
        entry.setBaseUrl("http://localhost:8090");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        props.setInstances(List.of(entry));

        ReconciliationScheduler scheduler = newScheduler(props, providerOf(injectedStatic));

        assertThat(scheduler.registeredProviders())
                .extracting(DeploymentDiscoveryProvider::sourceId)
                .containsExactly(StaticDeploymentDiscoveryProvider.SOURCE_ID);
        assertThat(scheduler.registeredProviders()).hasSize(1);
    }

    @Test
    void empty_injected_and_empty_yml_registers_no_providers() {
        ReconciliationScheduler scheduler = newScheduler(emptyProperties(), providerOf());

        assertThat(scheduler.registeredProviders()).isEmpty();
    }

    @Test
    void startup_reconciliation_invokes_injected_provider_listInstances() {
        RecordingProvider dynamic = new RecordingProvider("dynamic-src");
        ReconciliationScheduler scheduler = newScheduler(emptyProperties(), providerOf(dynamic));

        scheduler.periodicReconciliation();

        assertThat(dynamic.listCalls.get()).isGreaterThanOrEqualTo(1);
    }

    private static ReconciliationScheduler newScheduler(
            DeploymentDiscoveryProperties properties,
            ObjectProvider<DeploymentDiscoveryProvider> injected) {
        AgentRegistryRepository repository = stubRepository();
        return new ReconciliationScheduler(
                repository,
                new AgentCardFetcher(),
                properties,
                null,
                injected);
    }

    private static DeploymentDiscoveryProperties emptyProperties() {
        return new DeploymentDiscoveryProperties();
    }
    private static AgentRegistryRepository stubRepository() {
        AgentRegistryRepository repository = mock(AgentRegistryRepository.class);
        when(repository.getLastProcessedRevision(anyString())).thenReturn(0L);
        when(repository.getSnapshotFingerprint(anyString())).thenReturn(Optional.empty());
        when(repository.listInstanceKeysBySource(anyString())).thenReturn(List.of());
        when(repository.listDrainingPastGrace(any())).thenReturn(List.of());
        when(repository.listExpiredLeases(any())).thenReturn(List.of());
        return repository;
    }

    @SafeVarargs
    private static ObjectProvider<DeploymentDiscoveryProvider> providerOf(
            DeploymentDiscoveryProvider... providers) {
        return new ObjectProvider<>() {
            @Override
            public DeploymentDiscoveryProvider getObject() throws BeansException {
                if (providers.length == 0) {
        throw new NoSuchBeanDefinitionException(DeploymentDiscoveryProvider.class);
    }
                return providers[0];
            }

            @Override
            public Stream<DeploymentDiscoveryProvider> stream() {
                    return Arrays.stream(providers);
                }
        };
    }

    private static final class RecordingProvider implements DeploymentDiscoveryProvider {
        private final String sourceId;
        private final AtomicBoolean watchSubscribed = new AtomicBoolean(false);
        private final AtomicInteger listCalls = new AtomicInteger();

        private RecordingProvider(String sourceId) {
            this.sourceId = sourceId;
        }
        @Override
        public String sourceId() {
            return sourceId;
        }
        @Override
        public ListDeploymentInstancesResult listInstances() {
            listCalls.incrementAndGet();
            return new ListDeploymentInstancesResult(sourceId, 1L, List.of());
        }

        @Override
        public void watchInstances(DeploymentInstanceEventConsumer consumer) {
            watchSubscribed.set(true);
        }
    }
}
