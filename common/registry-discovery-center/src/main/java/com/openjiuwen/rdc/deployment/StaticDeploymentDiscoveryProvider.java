/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.deployment;

import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEvent;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEventConsumer;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEventType;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.deployment.Readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Static config-backed {@link DeploymentDiscoveryProvider} with snapshot diff watch.
 *
 * @since 0.1.0 (2026)
 */
public final class StaticDeploymentDiscoveryProvider implements DeploymentDiscoveryProvider {

    /**
     * SOURCE_ID.
     *
     * @since 0.1.0
     */
    public static final String SOURCE_ID = "static-config";

    private final List<StaticInstanceConfig> instances;
    private final AtomicLong revision = new AtomicLong(1);
    private volatile DeploymentInstanceEventConsumer eventConsumer;
    private volatile Map<String, StaticInstanceConfig> lastEmitted = Map.of();

    public StaticDeploymentDiscoveryProvider(List<StaticInstanceConfig> instances) {
        this.instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
    }

    /**
     * sourceId.
     *
     * @return result
     * @since 0.1.0
     */
    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    /**
     * listInstances.
     *
     * @return result
     * @since 0.1.0
     */
    @Override
    public ListDeploymentInstancesResult listInstances() {
        long rev = revision.incrementAndGet();
        Instant now = Instant.now();
        List<DeploymentInstanceObservation> observations = new ArrayList<>();
        for (StaticInstanceConfig cfg : instances) {
            observations.add(toObservation(cfg, rev, now));
        }
        emitDiff(observations, rev);
        return new ListDeploymentInstancesResult(SOURCE_ID, rev, observations);
    }

    /**
     * watchInstances.
     *
     * @param consumer consumer
     * @since 0.1.0
     */
    @Override
    public void watchInstances(DeploymentInstanceEventConsumer consumer) {
        this.eventConsumer = Objects.requireNonNull(consumer, "consumer");
    }
    private void emitDiff(List<DeploymentInstanceObservation> current, long rev) {
        DeploymentInstanceEventConsumer consumer = this.eventConsumer;
        if (consumer == null) {
            return;
        }
        Map<String, StaticInstanceConfig> currentMap = new HashMap<>();
        for (StaticInstanceConfig cfg : instances) {
            currentMap.put(cfg.instanceId(), cfg);
        }
        for (DeploymentInstanceObservation obs : current) {
            StaticInstanceConfig prior = lastEmitted.get(obs.instanceId());
            DeploymentInstanceEventType type = prior == null
                    ? DeploymentInstanceEventType.ADDED
                    : DeploymentInstanceEventType.MODIFIED;
            if (prior != null && prior.readiness() != obs.readiness()) {
                type = obs.readiness() == Readiness.TERMINATING
                        ? DeploymentInstanceEventType.TERMINATING
                        : DeploymentInstanceEventType.MODIFIED;
            }
            consumer.onEvent(new DeploymentInstanceEvent(type, obs));
        }
        for (Map.Entry<String, StaticInstanceConfig> entry : lastEmitted.entrySet()) {
            if (!currentMap.containsKey(entry.getKey())) {
                StaticInstanceConfig cfg = entry.getValue();
                consumer.onEvent(new DeploymentInstanceEvent(
                        DeploymentInstanceEventType.DELETED,
                        toObservation(cfg, rev, Instant.now())));
            }
        }
        lastEmitted = currentMap;
    }

    private static DeploymentInstanceObservation toObservation(StaticInstanceConfig cfg,
                                                               long rev, Instant now) {
        return new DeploymentInstanceObservation(
                cfg.tenantId(),
                cfg.serviceId(),
                cfg.instanceId(),
                cfg.baseUrl(),
                cfg.deploymentVersion(),
                cfg.readiness(),
                SOURCE_ID,
                rev,
                now);
    }

    /**
     * StaticInstanceConfig.
     *
     * @param tenantId tenantId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @param baseUrl baseUrl
     * @param deploymentVersion deploymentVersion
     * @param readiness readiness
     * @return result
     * @since 0.1.0
     */
    public record StaticInstanceConfig(
            String tenantId,
            String serviceId,
            String instanceId,
            String baseUrl,
            String deploymentVersion,
            Readiness readiness
    ) {
        }
    }