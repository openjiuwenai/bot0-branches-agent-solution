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

import java.util.Optional;
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
 *
 * @since 2026-07-06
 */
@SpringBootApplication
@EnableConfigurationProperties(DeepResearchSpringProperties.class)
public class DeepResearchRuntimeApplication {
    private static final int DOWNLOAD_CHUNK_SIZE = 65536;

    /**
     * Spring Boot entry point.
     *
     * @param args standard command-line arguments forwarded to Spring
     */
    public static void main(String[] args) {
        SpringApplication.run(DeepResearchRuntimeApplication.class, args);
    }

    /**
     * Builds the deep-research {@link AgentHandler} SPI bean wired against
     * runtime-provided middleware, remote A2A installer, and (optional) sandbox factory.
     *
     * @param properties runtime configuration bound from {@code application.yml}
     * @param registrar optional middleware registrar provider
     * @param installer remote A2A tool installer
     * @param sandboxFactoryProvider optional sandbox client factory provider
     * @return the configured {@link AgentHandler}
     */
    @Bean
    AgentHandler deepResearchHandler(DeepResearchSpringProperties properties,
                                     ObjectProvider<MiddlewareAdapterRegistrar> registrar,
                                     RemoteA2aToolInstaller installer,
                                     ObjectProvider<AgentCoreSandboxClientFactory> sandboxFactoryProvider) {
        AgentCoreSandboxClientFactory sandboxFactory = sandboxFactoryProvider.getIfAvailable();
        Supplier<SandboxOps> sandboxOpsSupplier = sandboxFactory != null
                ? () -> resolveSandboxOps(sandboxFactory).orElse(null)
                : null;
        return new JiuwenCoreAgentExtHandler(
                DeepResearchAgentFactory.build(properties, sandboxOpsSupplier),
                registrar.getIfAvailable(),
                installer);
    }

    private static Optional<SandboxOps> resolveSandboxOps(AgentCoreSandboxClientFactory factory) {
        SandboxClient client = factory.create();
        if (client == null) {
            return Optional.empty();
        }
        return Optional.of(toSandboxOps(client));
    }

    private static SandboxOps toSandboxOps(SandboxClient client) {
        return new SandboxOps() {
            @Override
            public ExecResult executeCode(String code, int timeoutSeconds) {
                ExecuteCodeResult result = client.code()
                        .executeCode(code, "python", timeoutSeconds, null, null);
                if (result == null) {
                    return new ExecResult(false, -1, "", "", "sandbox returned null result");
                }
                ExecuteCodeData data = result.getData();
                String stdout = data != null && data.getStdout() != null ? data.getStdout() : "";
                String stderr = data != null && data.getStderr() != null ? data.getStderr() : "";
                Integer exit = data != null ? data.getExitCode() : null;
                int exitCode = exit != null ? exit : -1;
                boolean isOk = result.getCode() == 0 && exit != null && exit == 0;
                String message = isOk
                        ? ""
                        : "transport code=" + result.getCode() + " message=" + result.getMessage();
                return new ExecResult(isOk, exitCode, stdout, stderr, message);
            }

            @Override
            public Optional<String> downloadFile(String remotePath, String localPath) {
                DownloadFileResult dl = client.fs().downloadFile(
                        remotePath, localPath, true, true, false, DOWNLOAD_CHUNK_SIZE, null);
                if (dl == null || dl.getCode() != 0) {
                    return Optional.empty();
                }
                DownloadFileData data = dl.getData();
                if (data != null && data.getLocalPath() != null) {
                    return Optional.of(data.getLocalPath());
                }
                return Optional.of(localPath);
            }
        };
    }
}
