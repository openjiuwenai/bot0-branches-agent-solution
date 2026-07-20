/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEvent;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceEventType;
import com.openjiuwen.rdc.model.deployment.Readiness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class StaticDeploymentDiscoveryProviderTest {

    @Test
    void watch_emits_added_then_modified_on_subsequent_snapshots() {
        List<DeploymentInstanceEvent> events = new ArrayList<>();
        StaticDeploymentDiscoveryProvider provider = new StaticDeploymentDiscoveryProvider(List.of(
                new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                        "tenant-w", "svc-w", "inst-w",
                        "http://127.0.0.1:8090", "1.0.0", Readiness.READY)));
        provider.watchInstances(events::add);

        provider.listInstances();
        provider.listInstances();

        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        assertThat(events.get(0).type()).isEqualTo(DeploymentInstanceEventType.ADDED);
        assertThat(events.get(1).type()).isEqualTo(DeploymentInstanceEventType.MODIFIED);
    }
}
