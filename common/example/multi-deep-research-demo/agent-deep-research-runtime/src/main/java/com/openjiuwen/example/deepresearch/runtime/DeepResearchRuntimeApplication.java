/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime;

import com.openjiuwen.core.sysop.result.DownloadFileData;
import com.openjiuwen.core.sysop.result.DownloadFileResult;
import com.openjiuwen.core.sysop.result.ExecuteCodeData;
import com.openjiuwen.core.sysop.result.ExecuteCodeResult;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.example.deepresearch.DeepResearchAgentFactory;
import com.openjiuwen.example.deepresearch.rail.ExecResult;
import com.openjiuwen.example.deepresearch.rail.SandboxOps;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.function.Supplier;

/**
 * Wrapper Spring Boot app that builds the deep-research DeepAgent via the library-tier
 * factory and exposes it as the {@link AgentHandler} SPI bean expected by
 * {@code agent-runtime-java}. Long-term memory and skill use are provided by the
 * harness rails wired inside {@link DeepResearchAgentFactory}; remote sub-agents
 * (search / read / verify) are injected as A2A tools through
 * {@code openjiuwen.service.a2a.remote-agents.*}.
 *
 * <p>Sandbox wiring lives here: this module owns the adaptation between the
 * runtime SPI's {@link SandboxClient} (a core-java concrete class) and the
 * library tier's narrow {@link SandboxOps} interface. Library code never sees
 * {@code SandboxClient}; if the sandbox backend ever changes, only the adapter
 * in {@link #toSandboxOps} needs updating.
 */
@SpringBootApplication
@EnableConfigurationProperties(DeepResearchSpringProperties.class)
public class DeepResearchRuntimeApplication {

    private static final int DOWNLOAD_CHUNK_SIZE = 65536;

    public static void main(String[] args) {
        SpringApplication.run(DeepResearchRuntimeApplication.class, args);
    }

    @Bean
    AgentHandler deepResearchHandler(DeepResearchSpringProperties properties,
                                     ObjectProvider<MiddlewareAdapterRegistrar> registrar,
                                     RemoteA2aToolInstaller installer,
                                     ObjectProvider<AgentCoreSandboxClientFactory> sandboxFactoryProvider) {
        AgentCoreSandboxClientFactory sandboxFactory = sandboxFactoryProvider.getIfAvailable();
        Supplier<SandboxOps> sandboxOpsSupplier = sandboxFactory != null
                ? () -> toSandboxOps(sandboxFactory.create())
                : null;
        return new JiuwenCoreAgentExtHandler(
                DeepResearchAgentFactory.build(properties, sandboxOpsSupplier),
                registrar.getIfAvailable(),
                installer);
    }

    private static SandboxOps toSandboxOps(SandboxClient client) {
        if (client == null) {
            return null;
        }
        return new SandboxOps() {
            @Override
            public ExecResult executeCode(String code, int timeoutSeconds) {
                ExecuteCodeResult r = client.code().executeCode(code, "python", timeoutSeconds, null, null);
                if (r == null) {
                    return new ExecResult(false, -1, "", "", "sandbox returned null result");
                }
                ExecuteCodeData d = r.getData();
                String stdout = d != null && d.getStdout() != null ? d.getStdout() : "";
                String stderr = d != null && d.getStderr() != null ? d.getStderr() : "";
                Integer exit = d != null ? d.getExitCode() : null;
                int exitCode = exit != null ? exit : -1;
                boolean ok = r.getCode() == 0 && exit != null && exit == 0;
                String message = ok ? "" : "transport code=" + r.getCode() + " message=" + r.getMessage();
                return new ExecResult(ok, exitCode, stdout, stderr, message);
            }

            @Override
            public String downloadFile(String remotePath, String localPath) {
                DownloadFileResult dl = client.fs().downloadFile(
                        remotePath, localPath, true, true, false, DOWNLOAD_CHUNK_SIZE, null);
                if (dl == null || dl.getCode() != 0) {
                    return null;
                }
                DownloadFileData d = dl.getData();
                return d != null && d.getLocalPath() != null ? d.getLocalPath() : localPath;
            }
        };
    }
}
