/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime;

import com.openjiuwen.core.sysop.result.ExecuteCmdData;
import com.openjiuwen.core.sysop.result.ExecuteCmdResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Startup smoke test that invokes a single {@code echo} through the runtime
 * {@code AgentCoreSandboxClientFactory} to verify jiuwenbox connectivity end-to-end.
 * Enabled only when {@code openjiuwen.demo.deep-research.sandbox.smoke-test=true}.
 *
 * @since 2026-07-06
 */
@Component
@ConditionalOnProperty(prefix = "openjiuwen.demo.deep-research.sandbox",
        name = "smoke-test", havingValue = "true")
public class SandboxSmokeTest implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SandboxSmokeTest.class);

    private final ObjectProvider<AgentCoreSandboxClientFactory> factoryProvider;

    /**
     * Creates the smoke-test runner with Spring-provided sandbox factory.
     *
     * @param factoryProvider Spring provider for {@link AgentCoreSandboxClientFactory}
     */
    public SandboxSmokeTest(ObjectProvider<AgentCoreSandboxClientFactory> factoryProvider) {
        this.factoryProvider = factoryProvider;
    }

    @Override
    public void run(ApplicationArguments args) {
        AgentCoreSandboxClientFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            LOG.warn("[sandbox-smoke] AgentCoreSandboxClientFactory bean absent - "
                    + "confirm openjiuwen.service.external.sandbox.enabled=true");
            return;
        }
        try {
            SandboxClient client = factory.create();
            LOG.info("[sandbox-smoke] SandboxClient created; running: echo hi_from_sandbox");
            ExecuteCmdResult result = client.shell().executeCmd(
                    "echo hi_from_sandbox", ".", 30, null, null);
            if (result == null) {
                LOG.error("[sandbox-smoke] result is null");
                return;
            }
            ExecuteCmdData data = result.getData();
            LOG.info("[sandbox-smoke] code={} message={} exitCode={} stdout={} stderr={}",
                    result.getCode(),
                    result.getMessage(),
                    data != null ? data.getExitCode() : null,
                    data != null ? data.getStdout() : null,
                    data != null ? data.getStderr() : null);
        } catch (RuntimeException ex) {
            LOG.error("[sandbox-smoke] failed", ex);
        }
    }
}
