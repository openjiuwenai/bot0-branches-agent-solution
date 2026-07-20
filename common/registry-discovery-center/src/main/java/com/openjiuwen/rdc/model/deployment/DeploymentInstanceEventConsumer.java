/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

/**
 * Consumes incremental deployment instance events from a
 * {@link DeploymentDiscoveryProvider}.
 *
 * @since 0.1.0 (2026)
  */
@FunctionalInterface
public interface DeploymentInstanceEventConsumer {

    void onEvent(DeploymentInstanceEvent event);
}
