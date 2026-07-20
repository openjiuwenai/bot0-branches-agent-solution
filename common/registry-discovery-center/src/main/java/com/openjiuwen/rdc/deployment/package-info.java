/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — deployment-discovery runtime bindings
 * (Feat-015).
 *
 * <p>Holds {@link com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties}
 * ({@code rdc.deployment-discovery.*}) and
 * {@link com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider}
 * (yml static instances, {@code sourceId=static-config}).
 *
 * <p>The SPI contract lives in {@code com.openjiuwen.rdc.model.deployment};
 * this package is the Spring-facing adapter. JDBC is forbidden.
 *
 * <p>Authority: Feat-015 provider reconcile path.
 */

package com.openjiuwen.rdc.deployment;
