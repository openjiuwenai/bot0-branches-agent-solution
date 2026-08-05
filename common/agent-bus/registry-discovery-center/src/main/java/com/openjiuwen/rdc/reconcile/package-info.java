/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — deployment-fact reconciliation
 * (Feat-015).
 *
 * <p>Hosts {@link com.openjiuwen.rdc.reconcile.ReconciliationScheduler},
 * {@link com.openjiuwen.rdc.reconcile.ReconciliationService}, and
 * {@link com.openjiuwen.rdc.reconcile.SnapshotFingerprint}.
 *
 * <p>Orchestrates {@code DeploymentDiscoveryProvider} snapshots → Card fetch →
 * repository upsert / pending / logical catalog writes. JDBC is forbidden —
 * persistence goes through
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}.
 *
 * <p>Authority: Feat-015 formal registration path (non-push).
 */

package com.openjiuwen.rdc.reconcile;
