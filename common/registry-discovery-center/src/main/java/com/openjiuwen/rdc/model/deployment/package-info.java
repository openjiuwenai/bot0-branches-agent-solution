/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — deployment discovery SPI
 * (Feat-015).
 *
 * <p>Pure-Java contracts for pluggable deployment fact sources:
 * {@link com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider},
 * observation / event types, readiness, and revision conflict / gap
 * exceptions.
 *
 * <p>No Spring / JDBC / Jackson. Runtime adapters live in
 * {@code com.openjiuwen.rdc.deployment}.
 *
 * <p>Authority: Feat-015 DeploymentDiscoveryProvider contract.
 */
package com.openjiuwen.rdc.model.deployment;
