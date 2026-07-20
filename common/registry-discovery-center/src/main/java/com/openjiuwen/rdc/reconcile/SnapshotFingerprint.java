/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import com.openjiuwen.rdc.card.CardDigest;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Deterministic fingerprint of a provider snapshot for revision conflict detection.

  * @since 0.1.0 (2026)
  */
final class SnapshotFingerprint {
    private SnapshotFingerprint() {

    }

    static String compute(ListDeploymentInstancesResult snapshot) {
        String canonical = snapshot.observations().stream()
                .sorted(Comparator
                        .comparing(DeploymentInstanceObservation::tenantId)
                        .thenComparing(DeploymentInstanceObservation::serviceId)
                        .thenComparing(DeploymentInstanceObservation::instanceId))
                .map(obs -> obs.tenantId() + "|"
                        + obs.serviceId() + "|"
                        + obs.instanceId() + "|"
                        + obs.internalBaseUrl() + "|"
                        + obs.readiness().name())
                .collect(Collectors.joining("\n"));
        return CardDigest.sha256(canonical);
    }
}
