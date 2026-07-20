/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Startup + periodic full reconciliation against configured deployment providers.
 *
 * <p>Providers = any Spring {@link DeploymentDiscoveryProvider} beans (dynamic /
 * K8s / test doubles) <strong>plus</strong>, when
 * {@code rdc.deployment-discovery.instances} is non-empty, the built-in static
 * yml provider. Observations without a matching yml binding use
 * {@code rdc.deployment-discovery.binding-defaults}.
 *
 * @since 0.1.0 (2026)
 */
@Component
@ConditionalOnProperty(prefix = "rdc.deployment-discovery", name = "enabled", havingValue = "true")
public class ReconciliationScheduler implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final List<DeploymentDiscoveryProvider> providers;

    public ReconciliationScheduler(AgentRegistryRepository repository,
                                   AgentCardFetcher cardFetcher,
                                   DeploymentDiscoveryProperties properties,
                                   RegistryObservabilityConfig observability,
                                   ObjectProvider<DeploymentDiscoveryProvider> injectedProviders) {
        Objects.requireNonNull(properties, "properties");
        List<ReconciliationService.StaticInstanceRuntimeBinding> bindings =
                buildStaticBindings(properties);
        this.reconciliationService = new ReconciliationService(
                repository, cardFetcher, properties, bindings, observability);
        this.providers = registerProviders(injectedProviders, properties, reconciliationService);
    }

    private static List<ReconciliationService.StaticInstanceRuntimeBinding> buildStaticBindings(
            DeploymentDiscoveryProperties properties) {
        List<ReconciliationService.StaticInstanceRuntimeBinding> bindings = new ArrayList<>();
        for (DeploymentDiscoveryProperties.StaticInstanceEntry entry : properties.getInstances()) {
            FrameworkType frameworkType = entry.getFrameworkType() != null
                    ? entry.getFrameworkType()
                    : properties.getBindingDefaults().getFrameworkType();
            if (frameworkType == null) {
                frameworkType = FrameworkType.JIUWEN;
            }
            bindings.add(new ReconciliationService.StaticInstanceRuntimeBinding(
                    entry.getTenantId(),
                    entry.getServiceId(),
                    entry.getInstanceId(),
                    frameworkType,
                    entry.getRouteKey(),
                    entry.getContractVersion(),
                    entry.getCapabilityVersion(),
                    entry.getCardPath(),
                    entry.getHeaders(),
                    entry.getMaxConcurrency() != null ? entry.getMaxConcurrency() : 10,
                    entry.getWeight() != null ? entry.getWeight() : 100,
                    entry.getRegion()));
        }
        return bindings;
    }

    private static List<DeploymentDiscoveryProvider> registerProviders(
            ObjectProvider<DeploymentDiscoveryProvider> injectedProviders,
            DeploymentDiscoveryProperties properties,
            ReconciliationService reconciliationService) {
        List<DeploymentDiscoveryProvider> all = new ArrayList<>();
        for (DeploymentDiscoveryProvider provider : injectedProviders) {
            if (provider instanceof StaticDeploymentDiscoveryProvider) {
                // Built-in static provider is constructed below from yml; skip duplicates.
                continue;
            }
            provider.watchInstances(reconciliationService::reconcileEvent);
            all.add(provider);
            LOG.info("registered deployment discovery provider sourceId={}", provider.sourceId());
        }
        List<StaticDeploymentDiscoveryProvider.StaticInstanceConfig> providerInstances =
                buildProviderInstances(properties);
        if (!providerInstances.isEmpty()) {
            StaticDeploymentDiscoveryProvider staticProvider =
                    new StaticDeploymentDiscoveryProvider(providerInstances);
            staticProvider.watchInstances(reconciliationService::reconcileEvent);
            all.add(staticProvider);
            LOG.info("registered static-config deployment discovery provider ({} instance(s))",
                    providerInstances.size());
        }
        if (all.isEmpty()) {
            LOG.warn("deployment-discovery enabled but no providers registered "
                    + "(add Spring beans of type DeploymentDiscoveryProvider and/or "
                    + "rdc.deployment-discovery.instances)");
        }
        return List.copyOf(all);
    }

    private static List<StaticDeploymentDiscoveryProvider.StaticInstanceConfig> buildProviderInstances(
            DeploymentDiscoveryProperties properties) {
        List<StaticDeploymentDiscoveryProvider.StaticInstanceConfig> providerInstances = new ArrayList<>();
        for (DeploymentDiscoveryProperties.StaticInstanceEntry entry : properties.getInstances()) {
            Readiness readiness = "TERMINATING".equalsIgnoreCase(entry.getReadiness())
                    ? Readiness.TERMINATING : Readiness.READY;
            providerInstances.add(new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                    entry.getTenantId(),
                    entry.getServiceId(),
                    entry.getInstanceId(),
                    entry.getBaseUrl(),
                    entry.getDeploymentVersion(),
                    readiness));
        }
        return providerInstances;
    }

    /** Visible for tests — order: injected beans first, then optional static-config. */
    List<DeploymentDiscoveryProvider> registeredProviders() {
        return providers;
    }
    /**
     * onApplicationEvent.
     *
     * @param event event
     * @since 0.1.0
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        runReconciliation("startup");
    }
    /**
     * periodicReconciliation.
     *
     * @since 0.1.0
     */
    @Scheduled(fixedDelayString = "${rdc.deployment-discovery.reconcile-interval:60s}")
    public void periodicReconciliation() {
        runReconciliation("periodic");
    }
    private void runReconciliation(String trigger) {
        for (DeploymentDiscoveryProvider provider : providers) {
            ReconciliationService.ReconciliationResult result = reconciliationService.reconcile(provider);
            if (result.success()) {
                LOG.info("reconciliation {} source={} revision={} created={} updated={} draining={}",
                        trigger, result.sourceId(), result.sourceRevision(),
                        result.created(), result.updated(), result.draining());
            } else {
                LOG.warn("reconciliation {} source={} failed: {}",
                        trigger, result.sourceId(), result.message());
            }
        }
    }
}
