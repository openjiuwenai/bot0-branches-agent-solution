/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.card.CardDigest;
import com.openjiuwen.rdc.card.RouteTargetDeriver;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEvent;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEventType;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.deployment.SourceRevisionConflictException;
import com.openjiuwen.rdc.model.deployment.SourceRevisionGapException;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.InstanceKey;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.ReconcilePendingCommand;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.ReconcileUpsertCommand;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles {@link DeploymentDiscoveryProvider} snapshots with the registry
 * index (Feat-015 0711 §5.1.3–5.1.5).
 *
 * @since 0.1.0 (2026)
 */
public final class ReconciliationService {
    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);

    private final AgentRegistryRepository repository;
    private final AgentCardFetcher cardFetcher;
    private final DeploymentDiscoveryProperties properties;
    private final List<StaticInstanceRuntimeBinding> bindings;
    private final RegistryObservabilityConfig observability;

    public ReconciliationService(AgentRegistryRepository repository,
                                 AgentCardFetcher cardFetcher,
                                 DeploymentDiscoveryProperties properties,
                                 List<StaticInstanceRuntimeBinding> bindings) {
        this(repository, cardFetcher, properties, bindings, null);
    }
    public ReconciliationService(AgentRegistryRepository repository,
                                 AgentCardFetcher cardFetcher,
                                 DeploymentDiscoveryProperties properties,
                                 List<StaticInstanceRuntimeBinding> bindings,
                                 RegistryObservabilityConfig observability) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cardFetcher = Objects.requireNonNull(cardFetcher, "cardFetcher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        this.observability = observability;
    }

    /**
     * reconcile.
     *
     * @param provider provider
     * @return result
     * @since 0.1.0
     */
    public ReconciliationResult reconcile(DeploymentDiscoveryProvider provider) {
        String sourceId = provider.sourceId();
        try {
            ListDeploymentInstancesResult snapshot = provider.listInstances();
            sourceId = snapshot.sourceId();
            long revision = snapshot.sourceRevision();
            long lastRevision = repository.getLastProcessedRevision(sourceId);
            String fingerprint = SnapshotFingerprint.compute(snapshot);

            if (revision < lastRevision) {
                LOG.info("skip stale snapshot source={} revision={} lastRevision={}",
                        sourceId, revision, lastRevision);
                int draining = reconcileMissingFromSnapshot(snapshot);
                runPostReconcileMaintenance(sourceId);
                return ReconciliationResult.success(sourceId, revision, 0, 0, draining);
            }
            if (revision == lastRevision && lastRevision > 0) {
                Optional<String> priorFingerprint = repository.getSnapshotFingerprint(sourceId);
                if (priorFingerprint.isPresent() && !priorFingerprint.get().equals(fingerprint)) {
                    throw new SourceRevisionConflictException(sourceId, revision,
                            "conflicting snapshot at revision " + revision);
                }
                // listInstances() may have synchronously fired watch events that bumped
                // lastRevision before applySnapshot ran; still reconcile DB rows missing
                // from the current snapshot (e.g. instance removed from static config).
                int draining = reconcileMissingFromSnapshot(snapshot);
                runPostReconcileMaintenance(sourceId);
                return ReconciliationResult.success(sourceId, revision, 0, 0, draining);
            }

            ReconciliationCounters counters = applySnapshot(snapshot);
            repository.updateLastProcessedRevision(sourceId, revision, fingerprint);
            repository.markSourceFresh(sourceId);
            runPostReconcileMaintenance(sourceId);
            return ReconciliationResult.success(sourceId, revision,
                    counters.created(), counters.updated(), counters.draining());
        } catch (SourceRevisionConflictException ex) {
            LOG.warn("source revision conflict for {} at revision {}: {}",
                    sourceId, ex.sourceRevision(), ex.getMessage());
            if (observability != null) {
                observability.observeReconciliationConflict(sourceId, ex.sourceRevision());
            }
            markSourceStale(sourceId);
            return ReconciliationResult.failure(
                    sourceId, "SOURCE_REVISION_GAP", ex.getMessage(), null);
        } catch (SourceRevisionGapException ex) {
            LOG.warn("source revision gap for {}: {}", sourceId, ex.getMessage());
            markSourceStale(sourceId);
            return ReconciliationResult.failure(
                    sourceId, "SOURCE_REVISION_GAP", ex.getMessage(), null);
        } catch (RuntimeException ex) {
            LOG.warn("reconciliation source {} unavailable: {}", sourceId, ex.getMessage());
            markSourceStale(sourceId);
            return ReconciliationResult.failure(
                    sourceId, "DEPLOYMENT_SOURCE_UNAVAILABLE", ex.getMessage(), null);
        }
    }

    /**
     * reconcileEvent.
     *
     * @param event event
     * @since 0.1.0
     */
    public void reconcileEvent(DeploymentInstanceEvent event) {
        try {
            DeploymentInstanceObservation obs = event.observation();
            if (obs.sourceRevision() < repository.getLastProcessedRevision(obs.sourceId())) {
                return;
            }
            if (event.type() == DeploymentInstanceEventType.DELETED
                    || event.type() == DeploymentInstanceEventType.TERMINATING) {
                String agentId = AgentIdCodec.derive(obs.tenantId(), obs.serviceId());
                repository.markDraining(obs.tenantId(), agentId, obs.instanceId());
                emitDraining(obs.sourceId(), obs.tenantId(), agentId);
                repository.updateLastProcessedRevision(obs.sourceId(), obs.sourceRevision());
                return;
            }
            reconcileObservation(obs);
            repository.updateLastProcessedRevision(obs.sourceId(), obs.sourceRevision());
        } catch (RuntimeException ex) {
            LOG.warn("reconcileEvent failed for source {}: {}",
                    event != null && event.observation() != null ? event.observation().sourceId() : "?",
                    ex.getMessage(), ex);
        }
    }

    private int reconcileMissingFromSnapshot(ListDeploymentInstancesResult snapshot) {
        return reconcileMissing(snapshot.sourceId(), seenInstanceIds(snapshot));
    }
    private static Set<String> seenInstanceIds(ListDeploymentInstancesResult snapshot) {
        Set<String> seen = new HashSet<>();
        for (DeploymentInstanceObservation obs : snapshot.observations()) {
            seen.add(obs.instanceId());
        }
        return seen;
    }

    private ReconciliationCounters applySnapshot(ListDeploymentInstancesResult snapshot) {
            String sourceId = snapshot.sourceId();
            Set<String> seen = seenInstanceIds(snapshot);
            ReconciliationCounters counters = new ReconciliationCounters();
            for (DeploymentInstanceObservation obs : snapshot.observations()) {
            ReconcileAction action = reconcileObservation(obs);
            switch (action) {
            case CREATED -> counters.created++;
            case UPDATED -> counters.updated++;
            case DRAINING -> counters.draining++;
            case FAILED, UNCHANGED -> LOG.trace(
            "reconcile action {} not counted in apply counters", action);
            }
        }
        counters.draining += reconcileMissing(sourceId, seen);
        return counters;
    }

    private ReconcileAction reconcileObservation(DeploymentInstanceObservation obs) {
        StaticInstanceRuntimeBinding binding = bindingFor(obs);
        String agentId = AgentIdCodec.derive(obs.tenantId(), obs.serviceId());
        String instancePk = obs.instanceId();

        if (obs.readiness() == Readiness.TERMINATING) {
            return handleTerminatingObservation(obs, agentId, instancePk);
        }

        AgentCardFetcher.FetchResult fetched = cardFetcher.fetchValidated(
                URI.create(obs.internalBaseUrl()),
                binding.cardPath(),
                binding.headers());
        if (!fetched.success()) {
            return handleCardFetchFailure(obs, binding, agentId, instancePk, fetched);
        }

        String cardJson = fetched.cardJson();
        String digest = CardDigest.sha256(cardJson);
        Optional<String> priorDigest = repository.findCardDigest(obs.tenantId(), agentId, instancePk);
        Optional<ReconcileAction> unchanged = reconcileSameDigestObservation(
                obs, agentId, instancePk, digest, priorDigest);
        if (unchanged.isPresent()) {
            return unchanged.get();
        }

        return upsertReconciledObservation(new UpsertReconcileArgs(
                obs, binding, agentId, instancePk, fetched, cardJson, digest, priorDigest));
    }

    private ReconcileAction handleTerminatingObservation(
            DeploymentInstanceObservation obs, String agentId, String instancePk) {
            repository.markDraining(obs.tenantId(), agentId, instancePk);
            emitDraining(obs.sourceId(), obs.tenantId(), agentId);
            return ReconcileAction.DRAINING;
        }

    private ReconcileAction handleCardFetchFailure(
            DeploymentInstanceObservation obs,
            StaticInstanceRuntimeBinding binding,
            String agentId,
            String instancePk,
            AgentCardFetcher.FetchResult fetched) {
        LOG.warn("card fetch failed tenant={} instance={}: {}",
                obs.tenantId(), obs.instanceId(), fetched.message());
        if (observability != null) {
            observability.observeCardRefreshFailed(
                    obs.sourceId(), obs.tenantId(), obs.instanceId(), fetched.failureCode());
        }
        Optional<String> existing = repository.findCardDigest(obs.tenantId(), agentId, instancePk);
        if (existing.isEmpty()) {
            repository.reconcilePending(new ReconcilePendingCommand(
                    obs.tenantId(), agentId, instancePk, obs.instanceId(),
                    obs.serviceId(), obs.sourceId(), obs.sourceRevision(),
                    obs.internalBaseUrl(), binding.frameworkType(), binding.region()));
            return ReconcileAction.FAILED;
        }
        repository.markRefreshDegraded(obs.tenantId(), agentId, instancePk);
        if (observability != null) {
            observability.observeUnhealthy(obs.tenantId(), agentId, "DEGRADED");
        }
        return ReconcileAction.FAILED;
    }

    private Optional<ReconcileAction> reconcileSameDigestObservation(
            DeploymentInstanceObservation obs,
            String agentId,
            String instancePk,
            String digest,
            Optional<String> priorDigest) {
        if (priorDigest.isEmpty() || !priorDigest.get().equals(digest)) {
            return Optional.empty();
        }
        // Successful re-validation clears prior card-refresh degradation (Feat-015 §5.1.3).
        repository.updateStatus(new AgentRegistryRepository.StatusUpdate(
                obs.tenantId(), agentId, instancePk, obs.instanceId(), "ONLINE", true));
        repository.clearLogicalRegistrationStaleCard(obs.tenantId(), obs.serviceId(), digest);
        // Same-digest path used to skip logical sync entirely; restore missing
        // source_ref so multi-instance catalogs cannot stay half-linked.
        boolean linked = repository.relinkLogicalSourceRef(new AgentRegistryRepository.RelinkLogicalSourceRefCommand(
                obs.tenantId(),
                obs.serviceId(),
                obs.instanceId(),
                digest,
                obs.sourceId(),
                obs.sourceRevision(),
                obs.internalBaseUrl()));
        if (linked) {
            return Optional.of(ReconcileAction.UNCHANGED);
        }
        return Optional.empty();
    }

    private ReconcileAction upsertReconciledObservation(UpsertReconcileArgs args) {
        DeploymentInstanceObservation obs = args.obs();
        StaticInstanceRuntimeBinding binding = args.binding();
        AgentCardFetcher.FetchResult fetched = args.fetched();
        String cardJson = args.cardJson();
        RouteTargetDeriver.DerivedRoute route = RouteTargetDeriver.derive(
                obs.internalBaseUrl(), cardJson, binding.routeKey());
        String agentName = RouteTargetDeriver.agentNameFromCard(cardJson);
        String contractVersion = fetched.contractVersion() != null && !fetched.contractVersion().isBlank()
                ? fetched.contractVersion()
                : (route.contractVersion() != null ? route.contractVersion() : binding.contractVersion());

        boolean created = args.priorDigest().isEmpty();
        repository.reconcileUpsert(new ReconcileUpsertCommand(
                obs.tenantId(),
                args.agentId(),
                args.instancePk(),
                obs.instanceId(),
                obs.serviceId(),
                obs.sourceId(),
                obs.sourceRevision(),
                agentName,
                binding.frameworkType(),
                binding.routeKey(),
                contractVersion,
                fetched.capabilityVersion() != null ? fetched.capabilityVersion() : binding.capabilityVersion(),
                obs.internalBaseUrl(),
                binding.maxConcurrency(),
                binding.weight(),
                binding.region(),
                cardJson,
                args.digest(),
                route.routeTargetJson(),
                "ACTIVE",
                "HEALTHY",
                "FRESH",
                "ONLINE"));
        return created ? ReconcileAction.CREATED : ReconcileAction.UPDATED;
    }

    private record UpsertReconcileArgs(
            DeploymentInstanceObservation obs,
            StaticInstanceRuntimeBinding binding,
            String agentId,
            String instancePk,
            AgentCardFetcher.FetchResult fetched,
            String cardJson,
            String digest,
            Optional<String> priorDigest) {
    }

    private int reconcileMissing(String sourceId, Set<String> seenInstanceIds) {
        int marked = 0;
        for (InstanceKey key : repository.listInstanceKeysBySource(sourceId)) {
            if (!seenInstanceIds.contains(key.instanceId())) {
                repository.markDraining(key.tenantId(), key.agentId(), key.instanceId());
                emitDraining(sourceId, key.tenantId(), key.agentId());
                marked++;
            }
        }
        return marked;
    }

    private void runPostReconcileMaintenance(String sourceId) {
        expireDrainingEntries(sourceId);
        expireLeaseEntries();
    }

    private void expireDrainingEntries(String sourceId) {
        Instant cutoff = Instant.now().minus(properties.getDrainingGracePeriod());
        for (InstanceKey key : repository.listDrainingPastGrace(cutoff)) {
            repository.markRemoved(key.tenantId(), key.agentId(), key.serviceId());
        }
    }

    private void expireLeaseEntries() {
            Instant now = Instant.now();
            for (InstanceKey key : repository.listExpiredLeases(now)) {
            repository.markRemoved(key.tenantId(), key.agentId(), key.serviceId());
            if (observability != null) {
            observability.observeLeaseExpired(key.tenantId(), key.agentId());
            }
        }
    }

    private void markSourceStale(String sourceId) {
        repository.markSourceStale(sourceId);
        if (observability != null) {
            observability.observeSourceStale(sourceId);
        }
    }

    private void emitDraining(String sourceId, String tenantId, String agentId) {
            if (observability != null) {
                observability.observeInstanceDraining(sourceId, tenantId, agentId);
            }
    }

    /**
     * Prefer an explicit {@code instances[]} binding; otherwise apply
     * {@link DeploymentDiscoveryProperties#getBindingDefaults()} so dynamic
     * providers can reconcile without a yml entry per pod.
     *
     * @param obs obs
     * @return result
     * @since 0.1.0
     */
    private StaticInstanceRuntimeBinding bindingFor(DeploymentInstanceObservation obs) {
        return bindings.stream()
                .filter(b -> b.tenantId().equals(obs.tenantId())
                        && b.serviceId().equals(obs.serviceId())
                        && b.instanceId().equals(obs.instanceId()))
                .findFirst()
                .orElseGet(() -> defaultBinding(obs));
    }

    private StaticInstanceRuntimeBinding defaultBinding(DeploymentInstanceObservation obs) {
        DeploymentDiscoveryProperties.BindingDefaults defaults = properties.getBindingDefaults();
        if (defaults == null) {
            defaults = new DeploymentDiscoveryProperties.BindingDefaults();
        }
        FrameworkType frameworkType = defaults.getFrameworkType() != null
                ? defaults.getFrameworkType()
                : FrameworkType.JIUWEN;
        String routeKey = blankToDefault(defaults.getRouteKey(), "/v1/query");
        String contractVersion = blankToDefault(defaults.getContractVersion(), "1.0.0");
        String capabilityVersion = blankToDefault(defaults.getCapabilityVersion(), "1.0.0");
        String cardPath = blankToDefault(defaults.getCardPath(), "/.well-known/agent-card.json");
        java.util.Map<String, String> headers = defaults.getHeaders() != null
                ? defaults.getHeaders()
                : java.util.Map.of();
        return new StaticInstanceRuntimeBinding(
                obs.tenantId(),
                obs.serviceId(),
                obs.instanceId(),
                frameworkType,
                routeKey,
                contractVersion,
                capabilityVersion,
                cardPath,
                headers,
                defaults.getMaxConcurrency() > 0 ? defaults.getMaxConcurrency() : 10,
                defaults.getWeight() > 0 ? defaults.getWeight() : 100,
                defaults.getRegion());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * StaticInstanceRuntimeBinding.
     *
     * @param tenantId tenantId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @param frameworkType frameworkType
     * @param routeKey routeKey
     * @param contractVersion contractVersion
     * @param capabilityVersion capabilityVersion
     * @param cardPath cardPath
     * @param headers headers
     * @param maxConcurrency maxConcurrency
     * @param weight weight
     * @param region region
     * @return result
     * @since 0.1.0
     */
    public record StaticInstanceRuntimeBinding(
            String tenantId,
            String serviceId,
            String instanceId,
            FrameworkType frameworkType,
            String routeKey,
            String contractVersion,
            String capabilityVersion,
            String cardPath,
            java.util.Map<String, String> headers,
            int maxConcurrency,
            int weight,
            String region
    ) {

    }

    /**
     * ReconciliationResult.
     *
     * @param success success
     * @param sourceId sourceId
     * @param sourceRevision sourceRevision
     * @param created created
     * @param updated updated
     * @param draining draining
     * @param failureCode failureCode
     * @param message message
     * @param traceId traceId
     * @return result
     * @since 0.1.0
     */
    public record ReconciliationResult(
            boolean success,
            String sourceId,
            long sourceRevision,
            int created,
            int updated,
            int draining,
            String failureCode,
            String message,
            String traceId
    ) {
        static ReconciliationResult success(String sourceId, long revision,
                                            int created, int updated, int draining) {
            return new ReconciliationResult(
                    true, sourceId, revision, created, updated, draining, null, null, null);
        }

        static ReconciliationResult failure(String sourceId, String failureCode,
                                            String message, String traceId) {
            return new ReconciliationResult(
                    false, sourceId, -1, 0, 0, 0, failureCode, message, traceId);
        }
    }

    private static final class ReconciliationCounters {
        int created;
        int updated;
        int draining;

        int created() {
            return created;
        }
        int updated() {
            return updated;
        }
        int draining() {
            return draining;
        }
    }

    private enum ReconcileAction {
        CREATED, UPDATED, DRAINING, FAILED, UNCHANGED
    }
}
