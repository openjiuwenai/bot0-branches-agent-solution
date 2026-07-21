/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.pull.PullRegistrationProperties;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guards for mutually exclusive registration paths and card-fetch policy.
 *
 * @since 0.1.0 (2026)
 */
@Component
public class RegistrationPathGuard implements InitializingBean {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationPathGuard.class);

    private final PullRegistrationProperties pullRegistration;
    private final DeploymentDiscoveryProperties deploymentDiscovery;
    private final RdcCardFetchOptions cardFetchOptions;

    public RegistrationPathGuard(PullRegistrationProperties pullRegistration,
                                 DeploymentDiscoveryProperties deploymentDiscovery,
                                 RdcCardFetchOptions cardFetchOptions) {
        this.pullRegistration = pullRegistration;
        this.deploymentDiscovery = deploymentDiscovery;
        this.cardFetchOptions = cardFetchOptions;
    }

    @Override
    public void afterPropertiesSet() {
        if (pullRegistration.isEnabled() && deploymentDiscovery.isEnabled()) {
            throw new IllegalStateException(
                    "Cannot enable both rdc.pull-registration and rdc.deployment-discovery. "
                            + "pull-registration is deprecated; migrate to rdc.deployment-discovery.instances "
                            + "(note: agentId/serviceId identity rules differ — see README).");
        }
        if (pullRegistration.isEnabled()) {
            LOG.warn("rdc.pull-registration.enabled=true is deprecated and will be removed in a future "
                    + "release; migrate to rdc.deployment-discovery (agentId is derived via AgentIdCodec, "
                    + "serviceId/instanceId are operator-supplied).");
        }
        if (cardFetchOptions.getTargetCidrs() == null || cardFetchOptions.getTargetCidrs().isEmpty()) {
            LOG.warn("rdc.registry.card-fetch.target-cidrs is empty — Agent Card fetch allows any host. "
                    + "For production, set private/loopback CIDRs per Feat-015 §5.1.3 "
                    + "(e.g. 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).");
        }
    }
}
