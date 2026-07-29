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
import com.openjiuwen.example.deepresearch.customrest.DeepResearchCustomRestAdapter;
import com.openjiuwen.example.deepresearch.rail.ExecResult;
import com.openjiuwen.example.deepresearch.rail.SandboxOps;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;
import com.openjiuwen.spi.store.BaseKVStore;
import com.openjiuwen.spi.store.KVStoreFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final String SKILLHUB_ENABLED_PROPERTY = "openjiuwen.service.middleware.skillhub.enabled";
    private static final String SKILLHUB_ENABLED_ARG_PREFIX = "--" + SKILLHUB_ENABLED_PROPERTY + "=";
    private static final String SKILLHUB_ENABLED_ENV = "SKILLHUB_ENABLED";
    private static final String SKILLHUB_REMOTE_PROFILE = "skillhub-remote";

    /**
     * Spring Boot entry point. Auto-activates the {@code skillhub-remote} profile so
     * the profile-scoped log suppression in {@code application.yml} kicks in whenever
     * SkillHub is on. Mirrors agent-solution issue #30's decision to fix the SKILL.md
     * plaintext-log leak in the solution layer via profile-scoped {@code logging.level}
     * instead of touching agent-core.
     *
     * @param args standard command-line arguments forwarded to Spring
     */
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DeepResearchRuntimeApplication.class);
        if (isSkillHubEnabled(args)) {
            app.setAdditionalProfiles(SKILLHUB_REMOTE_PROFILE);
        }
        app.run(args);
    }

    private static boolean isSkillHubEnabled(String[] args) {
        // SIT harness passes Spring properties as `--key=value` launch args (SutStack.AgentBuilder.property).
        for (String arg : args) {
            if (arg != null && arg.startsWith(SKILLHUB_ENABLED_ARG_PREFIX)) {
                return Boolean.parseBoolean(arg.substring(SKILLHUB_ENABLED_ARG_PREFIX.length()));
            }
        }
        String sys = System.getProperty(SKILLHUB_ENABLED_PROPERTY);
        if (sys != null) {
            return Boolean.parseBoolean(sys);
        }
        String env = System.getenv(SKILLHUB_ENABLED_ENV);
        return env != null && Boolean.parseBoolean(env);
    }

    /**
     * Builds the deep-research {@link AgentHandler} SPI bean wired against
     * runtime-provided middleware, remote A2A installer, (optional) sandbox factory,
     * and (optional) runtime-managed Redis client.
     *
     * <p>When a {@link RuntimeRedisClient} bean is present (activated by the
     * {@code redis-checkpointer} profile via runtime-java's {@code RedisMiddlewareAutoConfiguration}),
     * a {@link BaseKVStore} is built on top of it and passed through so the harness
     * task-scoped Todolist writes go through Redis via the same connection pool that
     * checkpointer / A2A TaskStore use (§5.1.4). Otherwise the Todolist falls back to
     * its default file-backed storage.
     *
     * @param properties runtime configuration bound from {@code application.yml}
     * @param registrar optional middleware registrar provider
     * @param sandboxFactoryProvider optional sandbox client factory provider
     * @param runtimeRedisClientProvider optional runtime-managed Redis client provider;
     *     when present the Todolist is routed to Redis via {@code KVStoreFactory}
     * @param skillHubLocalDir SkillHub middleware's local staging dir (may be blank when the
     *     middleware is off); when non-blank it is merged into the demo's extra readable roots
     *     so the {@code readFile} rail can serve SKILL.md content
     * @return the configured {@link AgentHandler}
     */
    @Bean
    AgentHandler deepResearchHandler(DeepResearchSpringProperties properties,
                                     ObjectProvider<MiddlewareAdapterRegistrar> registrar,
                                     ObjectProvider<AgentCoreSandboxClientFactory> sandboxFactoryProvider,
                                     ObjectProvider<RuntimeRedisClient> runtimeRedisClientProvider,
                                     @Value("${openjiuwen.service.middleware.skillhub.local-dir:}")
                                     String skillHubLocalDir) {
        AgentCoreSandboxClientFactory sandboxFactory = sandboxFactoryProvider.getIfAvailable();
        Supplier<SandboxOps> sandboxOpsSupplier = sandboxFactory != null
                ? () -> resolveSandboxOps(sandboxFactory).orElse(null)
                : null;
        mergeSkillHubLocalDirIntoReadableRoots(properties, skillHubLocalDir);
        BaseKVStore kvStore = buildRuntimeBackedKvStore(runtimeRedisClientProvider.getIfAvailable())
                .orElse(null);
        return new JiuwenCoreAgentExtHandler(
                DeepResearchAgentFactory.build(properties, sandboxOpsSupplier, kvStore),
                registrar.getIfAvailable());
    }

    /**
     * Bridges the runtime-managed Redis client into a core-side {@link BaseKVStore} via
     * {@code KVStoreFactory}. The {@code "redis_client"} conf key is the contract exposed by
     * {@code RedisKVStoreProvider}: when present, the provider skips its own reflection-based
     * Jedis construction and adopts the supplied client wholesale. Duck typing at the
     * {@code RedisStore} layer matches {@link RuntimeRedisClient}'s Jedis-compatible method
     * names, so no wrapper is needed.
     *
     * @param runtimeRedisClient the runtime-managed client bean; {@code null} means Redis is off
     * @return a Redis-backed {@link BaseKVStore}, or {@link Optional#empty()} when no client is available
     */
    private static Optional<BaseKVStore> buildRuntimeBackedKvStore(RuntimeRedisClient runtimeRedisClient) {
        if (runtimeRedisClient == null) {
            return Optional.empty();
        }
        return Optional.of(KVStoreFactory.create("redis", Map.of("redis_client", runtimeRedisClient)));
    }

    /**
     * Auto-append the SkillHub middleware's {@code local-dir} to the demo's
     * {@code extra-readable-roots} so the {@code readFile} rail always trusts
     * whatever path SkillHub actually writes SKILL.md into. Without this merge,
     * an operator (or a SIT acceptance test like F005-DA-12) that overrides
     * {@code openjiuwen.service.middleware.skillhub.local-dir} to a temp path
     * would silently break skill consumption: the LLM would still call
     * {@code readFile}, but the rail would reject the path as outside its
     * allow-list. Merging here — rather than in the library-tier factory —
     * keeps {@code DeepResearchProperties} free of Spring-only concerns while
     * still guaranteeing the two knobs stay in sync.
     *
     * @param properties demo configuration whose {@code extra-readable-roots} is mutated in place
     * @param skillHubLocalDir SkillHub middleware's local staging directory; blank or {@code null}
     *     is a no-op (SkillHub off or misconfigured)
     */
    private static void mergeSkillHubLocalDirIntoReadableRoots(
            DeepResearchSpringProperties properties, String skillHubLocalDir) {
        if (skillHubLocalDir == null || skillHubLocalDir.isBlank()) {
            return;
        }
        List<String> roots = properties.getExtraReadableRoots();
        if (roots == null) {
            roots = new ArrayList<>();
            properties.setExtraReadableRoots(roots);
        }
        if (!roots.contains(skillHubLocalDir)) {
            roots.add(skillHubLocalDir);
        }
    }

    /**
     * Registers the Custom REST protocol adapter that maps deep-research's business
     * envelope to the internal A2A Task pipeline. Auto-configuration activates only
     * when {@code openjiuwen.service.custom-rest.query-path} is set in
     * {@code application.yml}, so the {@code /a2a/} and {@code /v1/query}
     * entrypoints remain the primary access paths.
     *
     * @param objectMapper Spring's default Jackson mapper (auto-configured)
     * @return the deep-research adapter implementing {@code CustomRestProtocolAdapter}
     */
    @Bean
    CustomRestProtocolAdapter deepResearchCustomRestAdapter(ObjectMapper objectMapper) {
        return new DeepResearchCustomRestAdapter(objectMapper);
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
