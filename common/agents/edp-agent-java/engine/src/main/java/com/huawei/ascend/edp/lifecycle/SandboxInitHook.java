/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.lifecycle;

import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.config.SandboxConfig;
import com.huawei.ascend.edp.service.SkillPackService;

import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.ContainerScope;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.config.SandboxIsolationConfig;
import com.openjiuwen.core.sysop.config.SandboxLauncherConfig;
import com.openjiuwen.core.sysop.sandbox.ContainerManager;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxRegistryBootstrap;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.spec.lifecycle.AgentInitHook;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 沙箱初始化生命周期钩子 -- 替代 EdpaExtHandler.performInit() 手动初始化。
 *
 * <p>享受 AgentLifecycleBootstrap 的 initFailFast 保障（失败快速终止整个应用）。
 * 创建 SysOperation 双模式门面并注册到 AgentLifecycleContext。</p>
 *
 * @since 2024-01-01
 *
 */

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(prefix = "edpa.agent.sandbox", name = "enabled", havingValue = "true")
public class SandboxInitHook implements AgentInitHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxInitHook.class);

    @Autowired
    private EdpaSpringBootConfig springBootConfig;

    @Autowired(required = false)
    @SuppressWarnings("rawtypes")
    private AgentCoreSandboxClientFactory sandboxClientFactory;

    @Autowired(required = false)
    private CredentialDecryptor credentialDecryptor;

    @Override
    /**
     * On init.
     *
     * @param context the context value
     */
    public void onInit(AgentLifecycleContext context) throws Exception {
        SandboxConfig config = springBootConfig.getSandbox();
        if (config == null || !config.isEnabled()) {
            LOGGER.info("[EDP-SANDBOX] Sandbox disabled, skipping init");
            return;
        }

        LOGGER.info("[EDP-SANDBOX] SandboxInitHook starting, mode={}, serviceUrl={}",
                config.isEnabled() ? "SANDBOX" : "LOCAL", config.getServiceUrl());

        // 1. 凭据解密
        String serviceUrl = decryptIfNeeded(config.getServiceUrl());

        // 2. 构建 LocalWorkConfig（LOCAL 路径，含白名单/危险命令拦截）
        LocalWorkConfig localWorkConfig = LocalWorkConfig.builder().workDir(System.getProperty("user.dir")).build();

        // 3. 构建 SandboxGatewayConfig（SANDBOX 路径，含 fallback_on_failure）
        SandboxGatewayConfig gatewayConfig = buildGatewayConfig(config, serviceUrl);

        // 4. 构建 SysOperationCard
        SysOperationCard sysOpCard = SysOperationCard.builder().id("edp_sysop")
                .mode(config.isEnabled() ? OperationMode.SANDBOX : OperationMode.LOCAL).workConfig(localWorkConfig)
                .gatewayConfig(gatewayConfig).build();

        // 5. 创建 SysOperation 和治理装饰 SandboxClient
        SandboxClient decoratedClient = createDecoratedSandboxClientIfNeeded(config);
        SysOperation sysOp = new SysOperation(sysOpCard);
        SandboxRegistryBootstrap.ensureInitialized();

        // 6. 注册到生命周期上下文
        context.setAttribute("sysOperation", sysOp);
        context.setAttribute("sandboxConfig", config);
        context.setAttribute("decoratedSandboxClient", decoratedClient);

        // 7. 自动创建沙箱容器（ContainerManager.acquire）
        acquireSandboxContainerIfNeeded(context, sysOp, gatewayConfig, config, decoratedClient);

        LOGGER.info("[EDP-SANDBOX] SandboxInitHook completed: mode={}, path={}", sysOpCard.getMode(),
                sandboxClientFactory != null ? "governed" : "direct");
    }

    private SandboxClient createDecoratedSandboxClientIfNeeded(SandboxConfig config) {
        if (sandboxClientFactory == null) {
            LOGGER.info("[EDP-SANDBOX] Path 1: SysOperation with direct SandboxClient");
            return null;
        }
        LOGGER.info("[EDP-SANDBOX] Path 2: Using AgentCoreSandboxClientFactory (governed)");
        try {
            SandboxClient decoratedClient = sandboxClientFactory.create();

            // 修复：Spring Boot YAML 绑定 Map<String, Object> 时会将嵌套列表转为带数字键的 LinkedHashMap，
            // 导致 jiuwenswarm Pydantic 校验失败（期望 JSON 数组，实际收到 JSON 对象）。
            // 此处将 SandboxInitHook 构建的 filesystem_policy（含正确 Java List）注入到 delegate 的 extraParams 中，
            // 确保 DecoratingSandboxClient 创建沙箱时传递正确的 filesystem_policy。
            injectFilesystemPolicyIntoDelegate(decoratedClient, config.getSkillDeployPath());
            LOGGER.info("[EDP-SANDBOX] DecoratingSandboxClient created and filesystem_policy injected");
            return decoratedClient;
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDP-SANDBOX] Failed to create DecoratingSandboxClient, using direct mode: {}",
                    e.getMessage());
            return null;
        }
    }

    private void acquireSandboxContainerIfNeeded(AgentLifecycleContext context, SysOperation sysOp,
            SandboxGatewayConfig gatewayConfig, SandboxConfig config, SandboxClient decoratedClient) {
        if (!config.isAutoCreateOnStartup()) {
            return;
        }
        try {
            ContainerManager containerMgr = new ContainerManager();
            String isolationKey = resolveIsolationKey(gatewayConfig);
            containerMgr.acquire(isolationKey, gatewayConfig);
            context.setAttribute("containerManager", containerMgr);
            LOGGER.info("[EDP-SANDBOX] Sandbox container acquired: key={}", isolationKey);

            // 8. 部署技能包到沙箱（阶段二新增）
            deploySkillsToSandbox(sysOp, gatewayConfig, config, decoratedClient);
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDP-SANDBOX] Sandbox container acquire failed: {}", e.getMessage());
            if (config.isFallbackOnFailure()) {
                LOGGER.info("[EDP-SANDBOX] fallback_on_failure=true, continuing with LOCAL fallback");
            } else {
                throw e; // initFailFast：沙箱创建失败且不允许降级时快速终止
            }
        }
    }

    // === 辅助方法 ===

    private String decryptIfNeeded(String value) {
        if (credentialDecryptor != null && value != null && !value.isBlank()) {
            return credentialDecryptor.decrypt(value);
        }
        return value;
    }

    private SandboxGatewayConfig buildGatewayConfig(SandboxConfig config, String serviceUrl) {
        ContainerScope scope = parseContainerScope(config.getContainerScope());

        SandboxIsolationConfig isolation = SandboxIsolationConfig.builder().containerScope(scope)
                .customId(config.getSandboxIdPrefix()).build();

        Map<String, Object> params = new HashMap<>();
        params.put("fallback_on_failure", config.isFallbackOnFailure());
        params.put("root_path", config.getSkillDeployPath());
        if (config.getExcludedCommands() != null && !config.getExcludedCommands().isBlank()) {
            params.put("excluded_commands", config.getExcludedCommands());
        }

        return SandboxGatewayConfig.builder().gatewayUrl(serviceUrl).timeoutSeconds(config.getExecTimeoutSeconds())
                .isolation(isolation)
                .launcherConfig(SandboxLauncherConfig.builder().launcherType("pre_deploy").sandboxType("jiuwenbox")
                        .baseUrl(serviceUrl).onStop(config.getOnStop())
                        .extraParams(buildSandboxPolicyExtraParams(config.getSkillDeployPath())).build())
                .params(params).build();
    }

    /**
     * 构建 jiuwenbox 沙箱文件系统策略 extraParams。
     *
     * <p>jiuwenswarm 默认使用 code-agent-policy.yaml，该策略将整个根目录以只读方式挂载，
     * read_write 为空。此处通过 policy_mode=append 追加可写路径，
     * 使技能部署路径在沙箱内可写，否则 uploadFile 会因 Read-only file system 失败。</p>
     *
     * @param skillDeployPath 技能部署路径（如 /app/skills）
     * @return extraParams Map，包含 policy 和 policy_mode
     *
     */

    private static Map<String, Object> buildSandboxPolicyExtraParams(String skillDeployPath) {
        Map<String, Object> filesystemPolicy = new HashMap<>();
        filesystemPolicy.put("read_write", List.of(skillDeployPath));
        filesystemPolicy.put("directories", List.of(Map.of("path", skillDeployPath, "permissions", "0755")));

        Map<String, Object> policy = new HashMap<>();
        policy.put("filesystem_policy", filesystemPolicy);

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("policy", policy);
        extraParams.put("policy_mode", "append");
        return extraParams;
    }

    private ContainerScope parseContainerScope(String value) {
        if (value == null || value.isBlank()) {
            return ContainerScope.SESSION;
        }
        try {
            return ContainerScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ContainerScope.SESSION;
        }
    }

    /**
     * 将 filesystem_policy 注入到 DecoratingSandboxClient 的 delegate.config 中。
     *
     * <p>Spring Boot YAML 绑定 {@code Map<String, Object>} 时，嵌套 YAML 列表会被转换为
     * 带数字键的 LinkedHashMap（如 {@code {"0": "/app/skills"}}），而非正确的 Java List
     * （如 {@code ["/app/skills"]}）。序列化为 JSON 后变成 {@code {"0":"/app/skills"}}
     * 而非 {@code ["/app/skills"]}，导致 jiuwenswarm Pydantic 校验失败。</p>
     *
     * <p>此方法使用 {@link #buildSandboxPolicyExtraParams(String)} 构建的含正确 Java List 的
     * filesystem_policy，覆盖 YAML 绑定的畸形数据，确保 DecoratingSandboxClient 创建沙箱时
     * 传递正确的 filesystem_policy（JSON 数组格式）。</p>
     *
     * @param decoratedClient DecoratingSandboxClient 实例
     * @param skillDeployPath 技能部署路径（如 /app/skills）
     *
     */

    private void injectFilesystemPolicyIntoDelegate(SandboxClient decoratedClient, String skillDeployPath) {
        try {
            SandboxGatewayConfig delegateConfig = decoratedClient.getConfig();
            if (delegateConfig != null && delegateConfig.getLauncherConfig() != null) {
                Map<String, Object> correctExtraParams = buildSandboxPolicyExtraParams(skillDeployPath);
                delegateConfig.getLauncherConfig().setExtraParams(correctExtraParams);
                LOGGER.info("[EDP-SANDBOX] filesystem_policy injected into DecoratingSandboxClient delegate config");
            }
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDP-SANDBOX] Failed to inject filesystem_policy into delegate config: {}", e.getMessage());
        }
    }

    /**
     * 包装 SandboxOperationSupport.resolveIsolationKey 以简化异常处理
     *
     * @param config the config value
     * @return the result
     */
    private String resolveIsolationKey(SandboxGatewayConfig config) {
        try {
            return com.openjiuwen.core.sysop.sandbox.SandboxOperationSupport.resolveIsolationKey(config);
        } catch (NullPointerException | IllegalArgumentException e) {
            return config.getIsolation() != null && config.getIsolation().getCustomId() != null
                    ? config.getIsolation().getCustomId()
                    : "edp_default";
        }
    }

    /**
     * 部署技能包到沙箱（阶段二新增）。
     *
     * <p>流程：packSkills → uploadFile → tar -xzf → cleanup</p>
     *
     * @param sysOp SysOperation 双模式门面
     * @param gatewayConfig 沙箱网关配置（用于解析技能目录路径）
     * @param config 沙箱配置（含 skillDeployPath）
     *
     * @param decoratedClient the decoratedClient value
     */

    private void deploySkillsToSandbox(SysOperation sysOp, SandboxGatewayConfig gatewayConfig, SandboxConfig config,
            SandboxClient decoratedClient) {
        // 1. 解析技能目录路径：scenarioHome/skills
        Path skillsDir = resolveSkillsDir().orElse(null);
        if (skillsDir == null || !Files.exists(skillsDir)) {
            LOGGER.info("[EDP-SANDBOX] Skills directory not found, skipping skill deployment: {}", skillsDir);
            return;
        }

        SkillPackService packService = new SkillPackService();
        Path tarGz = null;
        try {
            // 步骤1：打包技能目录
            tarGz = packService.packSkills(skillsDir);
            LOGGER.info("[EDP-SANDBOX] Skills packed: {} → {}", skillsDir, tarGz);

            // 步骤2：上传到沙箱 -- 优先使用治理装饰 SandboxClient（需求2路径）
            String remotePath = config.getSkillDeployPath() + "/skills.tar.gz";
            if (decoratedClient != null) {
                decoratedClient.fs().uploadFile(tarGz.toString(), remotePath, true, true, false, 0, null);
            } else {
                sysOp.fs().uploadFile(tarGz.toString(), remotePath, true, true, false, 0, null);
            }
            LOGGER.info("[EDP-SANDBOX] Skills uploaded to sandbox: {}, governed={}", remotePath,
                    decoratedClient != null);

            // 步骤3：在沙箱内解压 -- 优先使用治理装饰 SandboxClient（需求2路径）
            String deployPath = config.getSkillDeployPath();
            String untarCmd = "tar -xzf " + remotePath + " -C " + deployPath;
            if (decoratedClient != null) {
                decoratedClient.shell().executeCmd(untarCmd, deployPath, 30, null, null);
            } else {
                sysOp.shell().executeCmd(untarCmd, deployPath, 30, null, null);
            }
            LOGGER.info("[EDP-SANDBOX] Skills decompressed in sandbox: {}", deployPath);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[EDP-SANDBOX] Failed to deploy skills to sandbox: {}", e.getMessage());

            // 技能部署失败不终止应用（非 initFailFast 项），降级到无技能模式
        } finally {
            // 步骤4：清理临时文件（无论成功或失败都清理）
            packService.cleanup(tarGz);
        }
    }

    /**
     * 从 springBootConfig.scenarioHome 解析技能目录路径
     *
     * @return the result
     */
    private Optional<Path> resolveSkillsDir() {
        String scenarioHome = springBootConfig.getScenarioHome();
        if (scenarioHome == null || scenarioHome.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(scenarioHome).toAbsolutePath().normalize().resolve("skills"));
    }
}
