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
 * Startup guards for mutually exclusive registration paths and card-fetch policy
 * (PR #73 review scheme B: keep deprecated pull for 1–2 releases, fail-fast on dual enable).
 *
 * @since 0.1.0 (2026)
 */
@Component
public class RegistrationPathGuard implements InitializingBean {
    static final String DUAL_ENABLE_MESSAGE =
            "Cannot enable both rdc.pull-registration and rdc.deployment-discovery. "
                    + "pull-registration is deprecated; migrate to rdc.deployment-discovery.instances "
                    + "(note: agentId/serviceId identity rules differ — see README).";

    static final String PULL_DEPRECATED_WARN =
            "rdc.pull-registration.enabled=true is deprecated and will be removed in a future "
                    + "release; migrate to rdc.deployment-discovery (agentId is derived via AgentIdCodec, "
                    + "serviceId/instanceId are operator-supplied).";

    static final String EMPTY_CIDR_WARN =
            "rdc.registry.card-fetch.target-cidrs is empty — Agent Card fetch allows any host. "
                    + "For production, set private/loopback CIDRs per Feat-015 §5.1.3 "
                    + "(e.g. 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).";

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
        apply(pullRegistration.isEnabled(), deploymentDiscovery.isEnabled(),
                cardFetchOptions.getTargetCidrs() == null || cardFetchOptions.getTargetCidrs().isEmpty(),
                LOG);
    }

    /**
     * Pure policy for tests and startup.
     *
     * @param pullEnabled pull-registration.enabled
     * @param deploymentEnabled deployment-discovery.enabled
     * @param emptyTargetCidrs whether card-fetch.target-cidrs is empty
     * @param log logger (may be null in unit tests that only assert exceptions)
     */
    static void apply(boolean pullEnabled, boolean deploymentEnabled, boolean emptyTargetCidrs, Logger log) {
        if (pullEnabled && deploymentEnabled) {
            throw new IllegalStateException(DUAL_ENABLE_MESSAGE);
        }
        if (log != null) {
            if (pullEnabled) {
                log.warn(PULL_DEPRECATED_WARN);
            }
            if (emptyTargetCidrs) {
                log.warn(EMPTY_CIDR_WARN);
            }
        }
    }
}
