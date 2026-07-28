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

package com.huawei.ascend.edp.handler;

import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.EdpAgentConfig;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.EdpConfigValidator;
import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.config.EdpaTodolist;
import com.huawei.ascend.edp.config.GovernanceConfig;
import com.huawei.ascend.edp.config.GovernanceConfigLoader;
import com.huawei.ascend.edp.config.RedisConfig;
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.huawei.ascend.edp.config.TodoRedisProperties;
import com.huawei.ascend.edp.enhancer.EdpaAgentEnhancer;
import com.huawei.ascend.edp.stream.PlanrulePromptBuilder;
import com.huawei.ascend.edp.stream.SkillScriptsCollector;

import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.sysop.OperationMode;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.SysOperationCard;
import com.openjiuwen.core.sysop.config.ContainerScope;
import com.openjiuwen.core.sysop.config.LocalWorkConfig;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.config.SandboxIsolationConfig;
import com.openjiuwen.core.sysop.config.SandboxLauncherConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxRegistryBootstrap;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import redis.clients.jedis.exceptions.JedisConnectionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EDPAgent 运行时适配器（标准化改造版）。
 *
 * <p>继承 {@link JiuwenCoreAgentExtHandler}，接入 agent-runtime-java 的 A2A 执行链路，
 * 并自动获得 RemoteA2aToolInstaller 的远程工具注入能力。
 * 远端 versatile-agent 通过 application.yml 的 openjiuwen.service.a2a.remote-agents 声明，
 * 框架自动注入 RemoteA2aInterruptRail，LLM 调用 versatile-agent 工具时触发 a2a_delegate 中断，
 * 由 A2AEnabledServeOrchestrator + RemoteInvocationBatchCoordinator 接管远端调用与续传。</p>
 *
 * <p>核心变化（标准化改造）：</p>
 * <ul>
 *     <li>使用 {@link EdpaSpringBootConfig}（合并了原 EdpAgentProperties）统一获取所有配置。</li>
 *     <li>model/versatile/mcpsse 配置从 EdpaSpringBootConfig 嵌套结构获取，
 *         不再使用 EdpAgentConfig.Model/Versatile 或 EnvOverrides。</li>
 *     <li>调用完整10参数版 {@link EdpaAgentEnhancer#enhance}，
 *         包含 EdpaTodoRail/EdpaEventRail/ScriptsRail/EdpaToolRegistry 等全部业务增强。</li>
 *     <li>恢复 {@link EdpConfigValidator#validateModelConfig} 校验（Phase 1注释掉的）。
 *         versatile.url 校验已废弃（改用 A2A remote-agents）。</li>
 *     <li>Versatile 委派与续传改为标准 A2A 协议栈：VersatileDelegateRail 构造 a2a_delegate 中断，
 *         A2AEnabledServeOrchestrator 接管远端调用与续传，不再使用 VersatileInterruptRail / VersatilePassthroughBuffer / 自造续流分支。</li>
 * </ul>
 *
 * <p><b>排查指引（现场联调 / 问题定界定位）：</b></p>
 * <pre>
 *   grep "[EDP-LLM-EMPTY]"      → 快速定位 LLM 空 answer
 *   grep "[EDP-LLM-CONFIG]"     → 验证 LLM model/sampling 配置覆盖
 *   grep "[EDPA-DIAG]"          → 事件发射诊断 + 续流结果
 *   grep "[EDP-SANDBOX]"        → 沙箱初始化/执行诊断
 *   grep "EdpaExtHandler streamQuery" → 请求入口
 *   grep "RemoteA2aToolInstaller"     → 远端 A2A 工具自动注入
 * </pre>
 *
 * @since 2024-01-01
 *
 */

public class EdpaExtHandler extends JiuwenCoreAgentExtHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaExtHandler.class);

    /**
     * DeepAgent 外观对象
     */
    private DeepAgent deepAgent;

    /**
     * EDPAgent 标准配置（0707 已废弃 yamlPath，agent 定义从 governance/scenario 加载）
     */
    private EdpAgentConfig agentConfig;

    /**
     * EDPAgent 专有配置
     */
    private EdpConfig edpConfig;

    /**
     * Governance 配置
     */
    private GovernanceConfig governanceConfig;

    /**
     * EDPAgent Spring Boot 配置（合并版，含 model/versatile/mcpsse）
     */
    private EdpaSpringBootConfig springBootConfig;

    /**
     * 活动场景目录的绝对路径
     */
    private Path scenarioHomePath;

    public EdpaExtHandler(Object agentInstance) {
        super(agentInstance != null ? agentInstance : new Object());
    }

    /**
     * performInit() 返回的初始化产物容器。
     *
     */

    public static class InitResult {
        /**
         * DeepAgent 内部 Agent 实例
         */
        private Object agentInstance;

        /**
         * DeepAgent 外观对象
         */
        private DeepAgent deepAgent;

        /**
         * EDPAgent 标准配置
         */
        private EdpAgentConfig agentConfig;

        /**
         * EDPAgent 专有配置
         */
        private EdpConfig edpConfig;

        /**
         * Governance 配置
         */
        private GovernanceConfig governanceConfig;

        /**
         * EDPAgent Spring Boot 配置
         */
        private EdpaSpringBootConfig springBootConfig;

        /**
         * 活动场景目录的绝对路径
         */
        private Path scenarioHomePath;

        /**
         * SysOperation 双模式门面（sandbox.enabled=true 时非 null）。
         */
        private SysOperation sysOperation;

        /**
         * 沙箱网关配置（sandbox.enabled=true 时非 null）。
         */
        private SandboxGatewayConfig sandboxGatewayConfig;

        /**
         * 治理装饰 SandboxClient（需求2路径，sandboxClientFactory != null 时非 null）。
         */
        private SandboxClient decoratedSandboxClient;

        /**
         * Gets the agent instance.
         *
         * @return the result
         */
        public Object getAgentInstance() {
            return agentInstance;
        }

        /**
         * Sets the agent instance.
         *
         * @param agentInstance the agentInstance value
         */
        public void setAgentInstance(Object agentInstance) {
            this.agentInstance = agentInstance;
        }

        /**
         * Gets the deep agent.
         *
         * @return the result
         */
        public DeepAgent getDeepAgent() {
            return deepAgent;
        }

        /**
         * Sets the deep agent.
         *
         * @param deepAgent the deepAgent value
         */
        public void setDeepAgent(DeepAgent deepAgent) {
            this.deepAgent = deepAgent;
        }

        /**
         * Gets the agent config.
         *
         * @return the result
         */
        public EdpAgentConfig getAgentConfig() {
            return agentConfig;
        }

        /**
         * Sets the agent config.
         *
         * @param agentConfig the agentConfig value
         */
        public void setAgentConfig(EdpAgentConfig agentConfig) {
            this.agentConfig = agentConfig;
        }

        /**
         * Gets the edp config.
         *
         * @return the result
         */
        public EdpConfig getEdpConfig() {
            return edpConfig;
        }

        /**
         * Sets the edp config.
         *
         * @param edpConfig the edpConfig value
         */
        public void setEdpConfig(EdpConfig edpConfig) {
            this.edpConfig = edpConfig;
        }

        /**
         * Gets the governance config.
         *
         * @return the result
         */
        public GovernanceConfig getGovernanceConfig() {
            return governanceConfig;
        }

        /**
         * Sets the governance config.
         *
         * @param governanceConfig the governanceConfig value
         */
        public void setGovernanceConfig(GovernanceConfig governanceConfig) {
            this.governanceConfig = governanceConfig;
        }

        /**
         * Gets the spring boot config.
         *
         * @return the result
         */
        public EdpaSpringBootConfig getSpringBootConfig() {
            return springBootConfig;
        }

        /**
         * Sets the spring boot config.
         *
         * @param springBootConfig the springBootConfig value
         */
        public void setSpringBootConfig(EdpaSpringBootConfig springBootConfig) {
            this.springBootConfig = springBootConfig;
        }

        /**
         * Gets the scenario home path.
         *
         * @return the result
         */
        public Path getScenarioHomePath() {
            return scenarioHomePath;
        }

        /**
         * Sets the scenario home path.
         *
         * @param scenarioHomePath the scenarioHomePath value
         */
        public void setScenarioHomePath(Path scenarioHomePath) {
            this.scenarioHomePath = scenarioHomePath;
        }

        /**
         * Gets the sys operation.
         *
         * @return the result
         */
        public SysOperation getSysOperation() {
            return sysOperation;
        }

        /**
         * Sets the sys operation.
         *
         * @param sysOperation the sysOperation value
         */
        public void setSysOperation(SysOperation sysOperation) {
            this.sysOperation = sysOperation;
        }

        /**
         * Gets the sandbox gateway config.
         *
         * @return the result
         */
        public SandboxGatewayConfig getSandboxGatewayConfig() {
            return sandboxGatewayConfig;
        }

        /**
         * Sets the sandbox gateway config.
         *
         * @param sandboxGatewayConfig the sandboxGatewayConfig value
         */
        public void setSandboxGatewayConfig(SandboxGatewayConfig sandboxGatewayConfig) {
            this.sandboxGatewayConfig = sandboxGatewayConfig;
        }

        /**
         * Gets the decorated sandbox client.
         *
         * @return the result
         */
        public SandboxClient getDecoratedSandboxClient() {
            return decoratedSandboxClient;
        }

        /**
         * Sets the decorated sandbox client.
         *
         * @param decoratedSandboxClient the decoratedSandboxClient value
         */
        public void setDecoratedSandboxClient(SandboxClient decoratedSandboxClient) {
            this.decoratedSandboxClient = decoratedSandboxClient;
        }
    }

    /**
     * 静态初始化方法（Phase 2 合并版）。
     *
     * <p>使用 EdpaSpringBootConfig 统一获取所有配置参数。
     * model/versatile 从 Spring Boot 绑定获取。
     * 不再需要 EnvOverrides（Spring Boot ${ENV_VAR:default} 自动处理）。</p>
     *
     * <p>Todo 存储由 agent-core 的 TodoStorage SPI 提供，通过 DeepAgentConfig 的
     * todoStorageType/kvStoreConfig 配置，不再需要外部传入 RedisTodoStore。</p>
     *
     * @param config EDPAgent 合并后配置（含 scenarioHome/model/versatile/mcpsse）
     * @param agentName Agent 名称（从 openjiuwen.service.a2a.agent-name 配置读取）
     * @param decoratedSandboxClient the decoratedSandboxClient value
     * @return InitResult 包含真实 agent 实例和初始化产物
     */

    public static InitResult performInit(EdpaSpringBootConfig config, String agentName,
            SandboxClient decoratedSandboxClient) {
        LOGGER.info("EdpaExtHandler performInit start (Phase 2)");
        InitResult result = new InitResult();
        result.setSpringBootConfig(config);
        result.setAgentConfig(new EdpAgentConfig());
        result.setEdpConfig(new EdpConfig());
        Path yamlDir = Path.of("src/main/resources").toAbsolutePath().normalize();
        // 第三至五步：解析 scenarioHome、加载 Governance、配置校验 fail-fast。
        loadGovernanceAndValidate(result, config, yamlDir);

        // 第六步：从 Governance actrule 加载 Todo 数据层。
        ActRuleConfig actrule = result.getGovernanceConfig() != null
                ? result.getGovernanceConfig().getActrule() : null;
        EdpaTodolist edpaTodolist = loadTodoDataLayer(actrule);

        // 第七步：按 Governance 的 planrule 拼接系统提示词。
        String systemPrompt = buildSystemPrompt(result);

        // 第八步：构造 DeepAgentConfig（使用 EdpaSpringBootConfig.ModelConfig）。
        Path skillsDir = result.getScenarioHomePath() != null ? result.getScenarioHomePath().resolve("skills") : null;
        DeepAgentConfig deepAgentConfig = buildDeepAgentConfig(config, result.getEdpConfig(), actrule, systemPrompt,
                skillsDir);

        // 第九步：通过 HarnessFactory 创建 DeepAgent。
        // 使用确定性的 agent card ID（基于 agentName），确保不同实例对同一 agent 定义
        // 使用相同的 Redis checkpoint key（格式: {sessionId}:agent:{agentId}:agent_state_blobs）。
        // 否则 HarnessFactory.ensureCardIdentity() 会为每个实例生成随机 UUID，导致跨实例会话无法共享。
        AgentCard agentCard = AgentCard.builder().id(agentName).name(agentName).description("EDPAgent instance")
                .build();
        result.setDeepAgent(HarnessFactory.createDeepAgent(agentCard, deepAgentConfig, null));
        LOGGER.info("[EDPA-DIAG] Created DeepAgent with deterministic card id={}, name={}", agentCard.getId(),
                agentCard.getName());

        // 第十步：注册 Skill 目录。
        registerSkills(result.getDeepAgent(), skillsDir, agentName);

        // 第十一步：加载框架级、场景级、Skill 级话术。
        SysScriptsConfig sysScriptsConfig = loadSysScripts(result, yamlDir, skillsDir);

        // 第十二、十三步：注册内置业务工具和 Rails，并强制完成 DeepAgent 初始化。
        setupEnhanceContext(result, new EnhanceSetupParams(config, actrule, skillsDir, edpaTodolist,
                sysScriptsConfig, agentName, decoratedSandboxClient));
        result.getDeepAgent().ensureInitialized();
        result.setAgentInstance(result.getDeepAgent().getAgent());
        LOGGER.info("EdpaExtHandler performInit completed, agentId={}, deepAgent initialized={}, scenarioHome={}",
                agentName, result.getDeepAgent().isInitialized(), result.getScenarioHomePath());
        return result;
    }

    /**
     * 解析 scenarioHome 路径、加载 Governance 配置并执行配置校验（fail-fast）。
     *
     * <p>对应 performInit 的第三至五步：scenarioHome 解析、Governance 加载、
     * Model/Versatile/Scenario 配置校验。</p>
     *
     * @param result 初始化结果，承载 scenarioHomePath 与 governanceConfig
     * @param config EDPAgent 合并后配置
     * @param yamlDir 框架资源目录（src/main/resources）
     */
    private static void loadGovernanceAndValidate(InitResult result, EdpaSpringBootConfig config, Path yamlDir) {
        // 第三步：解析 scenarioHome 路径。
        String scenarioHome = config.getScenarioHome();
        if (scenarioHome != null && !scenarioHome.isBlank()) {
            result.setScenarioHomePath(Path.of(scenarioHome).toAbsolutePath().normalize());
            LOGGER.info("Scenario home resolved: {}", result.getScenarioHomePath());
        }

        // 第四步：加载 Governance 配置（框架级 + 场景级双路径合并）。
        Path frameworkGovernancePath = yamlDir.resolve("governance").toAbsolutePath().normalize();
        if (result.getScenarioHomePath() != null && Files.exists(result.getScenarioHomePath())) {
            Path scenarioGovernancePath = result.getScenarioHomePath()
                    .resolve("governance").toAbsolutePath().normalize();
            if (Files.exists(scenarioGovernancePath)) {
                try {
                    result.setGovernanceConfig(
                        GovernanceConfigLoader.loadWithPriority(scenarioGovernancePath, frameworkGovernancePath));
                    LOGGER.info("Governance loaded with priority: scenario={}, framework={}",
                        scenarioGovernancePath, frameworkGovernancePath);
                } catch (IllegalStateException e) {
                    LOGGER.warn("Failed to load governance config: {}", e.getMessage());
                }
            } else {
                try {
                    result.setGovernanceConfig(GovernanceConfigLoader.load(frameworkGovernancePath));
                    LOGGER.info("Governance loaded from framework only: {}", frameworkGovernancePath);
                } catch (IllegalStateException e) {
                    LOGGER.warn("Failed to load governance config: {}", e.getMessage());
                }
            }
        } else {
            try {
                result.setGovernanceConfig(GovernanceConfigLoader.load(frameworkGovernancePath));
                LOGGER.info("Governance loaded from framework only (no scenarioHome)");
            } catch (IllegalStateException e) {
                LOGGER.warn("Failed to load governance config: {}", e.getMessage());
            }
        }

        // 第五步：配置校验 fail-fast。
        EdpConfigValidator.validateModelConfig(config.getModel());
        // versatile.url 已废弃（改用 A2A remote-agents），不再校验 VersatileUrl。
        EdpConfigValidator.validateSandboxConfig(config.getSandbox());
        if (result.getScenarioHomePath() != null) {
            EdpConfigValidator.validateScenarioConfig(result.getScenarioHomePath());
        }
    }

    /**
     * 从 Governance actrule 加载 Todo 数据层。
     *
     * <p>对应 performInit 的第六步：当 actrule 含有 todolist 条目时构造
     * {@link EdpaTodolist}，加载失败时记录告警并返回 null。</p>
     *
     * @param actrule Governance 的 actrule 配置，可为 null
     * @return 构造完成的 EdpaTodolist，无条目或加载失败时返回 null
     */
    private static EdpaTodolist loadTodoDataLayer(ActRuleConfig actrule) {
        EdpaTodolist edpaTodolist = null;
        if (actrule != null && actrule.getTodolistEntries() != null && !actrule.getTodolistEntries().isEmpty()) {
            try {
                edpaTodolist = new EdpaTodolist(actrule.getTodolistEntries(), actrule.getTodolistDynamicPaths());
                LOGGER.info("EdpaTodolist loaded from governance actrule: entries={}, dynamicPaths={}",
                        edpaTodolist.getEntries().size(), edpaTodolist.getDynamicPaths().size());
            } catch (IllegalStateException e) {
                LOGGER.warn("Failed to load EdpaTodolist from governance actrule: {}", e.getMessage());
            }
        }
        return edpaTodolist;
    }

    /**
     * 按 Governance 的 planrule 拼接系统提示词。
     *
     * <p>对应 performInit 的第七步：优先使用 PlanrulePromptBuilder 生成提示词片段，
     * 其次回退到 AgentConfig.prompt.system，均不可用时使用空串。</p>
     *
     * @param result 初始化结果，用于获取 governanceConfig 与 agentConfig
     * @return 拼接后的系统提示词字符串
     */
    private static String buildSystemPrompt(InitResult result) {
        String systemPrompt = "";
        if (result.getGovernanceConfig() != null && result.getGovernanceConfig().getPlanrule() != null) {
            systemPrompt = PlanrulePromptBuilder.buildSystemPromptFragment(result.getGovernanceConfig().getPlanrule());
            LOGGER.info("System prompt built from PlanrulePromptBuilder, length={}", systemPrompt.length());
        } else if (result.getAgentConfig().getPrompt() != null) {
            systemPrompt = result.getAgentConfig().getPrompt().getSystem();
        } else {
            LOGGER.info("No system prompt source available, using empty default");
        }
        return systemPrompt;
    }

    /**
     * 加载框架级、场景级、Skill 级话术配置。
     *
     * <p>对应 performInit 的第十一步：依次加载 framework scriptconfig.yaml、
     * scenario scriptconfig.yaml，并合并 Skill 目录下收集到的话术。</p>
     *
     * @param result 初始化结果，用于获取 scenarioHomePath
     * @param yamlDir 框架资源目录（src/main/resources）
     * @param skillsDir 场景 Skill 目录，可为 null
     * @return 合并后的 SysScriptsConfig
     */
    private static SysScriptsConfig loadSysScripts(InitResult result, Path yamlDir, Path skillsDir) {
        SysScriptsConfig sysScriptsConfig = new SysScriptsConfig();
        Path frameworkScriptsPath = yamlDir.resolve("governance").resolve("scriptconfig.yaml").toAbsolutePath()
                .normalize();
        if (Files.exists(frameworkScriptsPath)) {
            // 文件系统优先（开发态）
            sysScriptsConfig.load(frameworkScriptsPath.toString());
            LOGGER.info("Framework scripts loaded from {}", frameworkScriptsPath);
        } else {
            // 文件系统不存在 → classpath 回退（集成态，引擎 JAR 内有 governance）
            boolean loadedFromClasspath = sysScriptsConfig.loadFromClasspath();
            if (loadedFromClasspath) {
                LOGGER.info("Framework scripts loaded from classpath (engine JAR)");
            } else {
                LOGGER.warn("Framework scripts not found in filesystem or classpath, using defaults");
            }
        }
        if (result.getScenarioHomePath() != null) {
            Path scenarioScriptsPath = result.getScenarioHomePath().resolve("governance").resolve("scriptconfig.yaml")
                    .toAbsolutePath().normalize();
            if (Files.exists(scenarioScriptsPath)) {
                sysScriptsConfig.load(scenarioScriptsPath.toString());
                LOGGER.info("Scenario scripts loaded from {}", scenarioScriptsPath);
            }
        }
        if (skillsDir != null && Files.exists(skillsDir)) {
            Map<String, String> skillScripts = SkillScriptsCollector.collectSkillScripts(skillsDir);
            sysScriptsConfig.mergeSkillScripts(skillScripts);
            LOGGER.info("Skill scripts collected: {} entries from {}", skillScripts.size(), skillsDir);
        }
        LOGGER.info("SysScriptsConfig merged templates: {}", sysScriptsConfig.getTemplates().size());
        return sysScriptsConfig;
    }

    /**
     * 注册 EDPAgent 内置业务工具和业务 Rails，并构造 enhance 上下文执行增强。
     *
     * <p>对应 performInit 的第十二步：配置沙箱门面与 SysOperation、
     * 组装 EnhanceContext 并调用 {@link EdpaAgentEnhancer#enhance}。</p>
     *
     * @param result 初始化结果，承载 sandboxGatewayConfig/sysOperation/deepAgent 等
     * @param config EDPAgent 合并后配置
     * @param actrule Governance 的 actrule 配置，可为 null
     * @param skillsDir 场景 Skill 目录，可为 null
     * @param edpaTodolist Todo 数据层，可为 null
     * @param result the InitResult to populate
     * @param params the enhance setup parameters
     */
    private static void setupEnhanceContext(InitResult result, EnhanceSetupParams params) {
        // --- 第十二步：注册 EDPAgent 内置业务工具和业务 Rails（13参数版，含沙箱）。
        // Versatile 委派由 VersatileDelegateRail 拦截 call_versatile 构造 a2a_delegate 中断，
        // 框架 A2AEnabledServeOrchestrator 接管远端调用与续传。
        // --- 沙箱特性：创建SysOperation双模式门面 ---
        if (params.config.getSandbox() != null && params.config.getSandbox().isEnabled()) {
            result.setSandboxGatewayConfig(buildSandboxGatewayConfig(params.config.getSandbox()));
        }
        result.setSysOperation(createSysOperationIfNeeded(params.config, result.getSandboxGatewayConfig())
                .orElse(null));
        result.setDecoratedSandboxClient(params.decoratedSandboxClient);

        // --- enhance调用合并 ---
        EdpaAgentEnhancer.EnhanceContext enhanceCtx = new EdpaAgentEnhancer.EnhanceContext();
        enhanceCtx.setEdpConfig(result.getEdpConfig());
        enhanceCtx.setSpringBootConfig(params.config);
        enhanceCtx.setActrule(params.actrule);
        enhanceCtx.setToolDataChannel(new ToolDataChannel());
        enhanceCtx.setSkillsDir(params.skillsDir);
        enhanceCtx.setDeepAgent(result.getDeepAgent());
        enhanceCtx.setEdpaTodolist(params.edpaTodolist);
        enhanceCtx.setScripts(params.sysScriptsConfig);
        enhanceCtx.setAgentName(params.agentName);
        enhanceCtx.setSysOp(result.getSysOperation());
        enhanceCtx.setGatewayConfig(result.getSandboxGatewayConfig());
        enhanceCtx.setDecoratedSandboxClient(params.decoratedSandboxClient);
        EdpaAgentEnhancer.enhance(result.getDeepAgent(), enhanceCtx);
    }

    /**
     * 封装 setupEnhanceContext 所需的参数。
     */
    private static final class EnhanceSetupParams {
        private final EdpaSpringBootConfig config;
        private final ActRuleConfig actrule;
        private final Path skillsDir;
        private final EdpaTodolist edpaTodolist;
        private final SysScriptsConfig sysScriptsConfig;
        private final String agentName;
        private final SandboxClient decoratedSandboxClient;

        EnhanceSetupParams(EdpaSpringBootConfig config, ActRuleConfig actrule, Path skillsDir,
                EdpaTodolist edpaTodolist, SysScriptsConfig sysScriptsConfig, String agentName,
                SandboxClient decoratedSandboxClient) {
            this.config = config;
            this.actrule = actrule;
            this.skillsDir = skillsDir;
            this.edpaTodolist = edpaTodolist;
            this.sysScriptsConfig = sysScriptsConfig;
            this.agentName = agentName;
            this.decoratedSandboxClient = decoratedSandboxClient;
        }
    }

    /**
     * 将 performInit() 返回的 InitResult 应用到当前实例。
     *
     * @param result the result value
     */

    public void applyInitResult(InitResult result) {
        this.deepAgent = result.getDeepAgent();
        this.agentConfig = result.getAgentConfig();
        this.edpConfig = result.getEdpConfig();
        this.governanceConfig = result.getGovernanceConfig();
        this.springBootConfig = result.getSpringBootConfig();
        this.scenarioHomePath = result.getScenarioHomePath();
    }

    // ===== 适配版 SPI 覆写方法 =====

    /**
     * 适配版 SPI：流式查询，委托给父类。
     *
     * <p>标准化改造后，Versatile 委派与续传完全由框架接管：</p>
     * <ul>
     *     <li>JiuwenCoreAgentExtHandler.streamQuery 执行前调用 RemoteA2aToolInstaller.install()
     *         自动注入 RemoteA2aInterruptRail（工具名 = remote-agents[].name）</li>
     *     <li>LLM 调用 versatile-agent 工具时触发 _interrupt_kind=a2a_delegate 中断</li>
     *     <li>A2AEnabledServeOrchestrator + RemoteInvocationBatchCoordinator 接管远端调用</li>
     *     <li>续传基于 taskStore.get(shadow:agentId:parentTaskId)，parentTaskId 兜底用 conversationId，
     *         前端用相同 contextId + 递增 messageId 即可恢复中断的 a2a_delegate 调用</li>
     * </ul>
     *
     * @param request the request value
     * @param observer the observer value
     */

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String conversationId = request.getConversationId();
        LOGGER.info("EdpaExtHandler streamQuery: conversationId={}", conversationId);
        super.streamQuery(request, observer);
    }

    /**
     * 适配版 SPI：同步查询。委托给父类。
     *
     * @param request the request value
     * @return the result
     */

    @Override
    public QueryResponse query(ServeRequest request) {
        return super.query(request);
    }

    // ===== 静态辅助方法 =====

    /**
     * 根据 SandboxConfig 创建 SysOperation（sandbox.enabled=true 时）。
     *
     * <p>SandboxInitHook 也会在 AgentLifecycleBootstrap 阶段创建 SysOperation 并注册到 AgentLifecycleContext，
     * 但 EdpaExtHandler.performInit() 运行在 Spring Bean 创建阶段，可能先于 Lifecycle 阶段。
     * 此处独立创建 SysOperation 供 Rail 注册使用，与 SandboxInitHook 的 ContainerManager.acquire() 互补。</p>
     *
     * @param config the config value
     * @param gatewayConfig the gatewayConfig value
     * @return the result
     */

    private static Optional<SysOperation> createSysOperationIfNeeded(EdpaSpringBootConfig config,
            SandboxGatewayConfig gatewayConfig) {
        if (config == null || config.getSandbox() == null || !config.getSandbox().isEnabled()) {
            return Optional.empty();
        }
        try {
            com.huawei.ascend.edp.config.SandboxConfig sandboxConfig = config.getSandbox();

            LocalWorkConfig localWorkConfig = LocalWorkConfig.builder().workDir(System.getProperty("user.dir")).build();

            SysOperationCard sysOpCard = SysOperationCard.builder().id("edp_sysop").mode(OperationMode.SANDBOX)
                    .workConfig(localWorkConfig).gatewayConfig(gatewayConfig).build();

            SandboxRegistryBootstrap.ensureInitialized();
            SysOperation sysOp = new SysOperation(sysOpCard);
            LOGGER.info("[EDP-SANDBOX] SysOperation created in performInit: mode={}, serviceUrl={}", sysOp.getMode(),
                    sandboxConfig.getServiceUrl());
            return Optional.of(sysOp);
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDP-SANDBOX] Failed to create SysOperation, falling back to ProcessBuilder: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 构建 SandboxGatewayConfig（与 SandboxInitHook.buildGatewayConfig 逻辑一致）。
     *
     * @param config the config value
     * @return the result
     */
    private static SandboxGatewayConfig buildSandboxGatewayConfig(com.huawei.ascend.edp.config.SandboxConfig config) {
        ContainerScope scope;
        try {
            scope = ContainerScope.valueOf(config.getContainerScope().toUpperCase());
        } catch (IllegalArgumentException e) {
            scope = ContainerScope.SESSION;
        }

        SandboxIsolationConfig isolation = SandboxIsolationConfig.builder().containerScope(scope)
                .customId(config.getSandboxIdPrefix()).build();

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("fallback_on_failure", config.isFallbackOnFailure());
        params.put("root_path", config.getSkillDeployPath());
        if (config.getExcludedCommands() != null && !config.getExcludedCommands().isBlank()) {
            params.put("excluded_commands", config.getExcludedCommands());
        }

        return SandboxGatewayConfig.builder().gatewayUrl(config.getServiceUrl())
                .timeoutSeconds(config.getExecTimeoutSeconds()).isolation(isolation)
                .launcherConfig(SandboxLauncherConfig.builder().launcherType("pre_deploy").sandboxType("jiuwenbox")
                        .baseUrl(config.getServiceUrl()).onStop(config.getOnStop())
                        .extraParams(buildSandboxPolicyExtraParams(config.getSkillDeployPath())).build())
                .params(params).build();
    }

    /**
     * 构建 jiuwenbox 沙箱文件系统策略 extraParams（与 SandboxInitHook 逻辑一致）。
     *
     * <p>jiuwenswarm 默认使用 code-agent-policy.yaml，该策略将整个根目录以只读方式挂载，
     * read_write 为空。此处通过 policy_mode=append 追加可写路径，
     * 使技能部署路径在沙箱内可写，否则 uploadFile 会因 Read-only file system 失败。</p>
     *
     * @param skillDeployPath 技能部署路径（如 /app/skills）
     * @return extraParams Map，包含 policy 和 policy_mode
     *
     */

    private static java.util.Map<String, Object> buildSandboxPolicyExtraParams(String skillDeployPath) {
        java.util.Map<String, Object> filesystemPolicy = new java.util.HashMap<>();
        filesystemPolicy.put("read_write", List.of(skillDeployPath));
        filesystemPolicy.put("directories", List.of(Map.of("path", skillDeployPath, "permissions", "0755")));

        java.util.Map<String, Object> policy = new java.util.HashMap<>();
        policy.put("filesystem_policy", filesystemPolicy);

        java.util.Map<String, Object> extraParams = new java.util.HashMap<>();
        extraParams.put("policy", policy);
        extraParams.put("policy_mode", "append");
        return extraParams;
    }

    /**
     * 构造 DeepAgentConfig（使用 EdpaSpringBootConfig.ModelConfig，Phase 2 合并版）。
     *
     * @param config the config value
     * @param edpConfig the edpConfig value
     * @param actrule the actrule value
     * @param systemPrompt the systemPrompt value
     * @param skillsDir the skillsDir value
     * @return the result
     */

    private static DeepAgentConfig buildDeepAgentConfig(EdpaSpringBootConfig config, EdpConfig edpConfig,
            ActRuleConfig actrule, String systemPrompt, Path skillsDir) {
        EdpaSpringBootConfig.ModelConfig model = config.getModel();

        Map<String, Object> modelMap = new LinkedHashMap<>();
        Map<String, Object> backendMap = new LinkedHashMap<>();

        if (model != null) {
            // 对齐 Python agent.py L138-141: [EDP-LLM-CONFIG] applied sampling override
            EdpConfig.LlmSampling sampling = edpConfig != null ? edpConfig.getLlmSampling() : null;
            LOGGER.info(
                    "[EDP-LLM-CONFIG] applied model config: provider={}, name={}, baseUrl={}, "
                            + "temperature={}, topP={}, maxRetries={}",
                    model.getProvider(), model.getName(), model.getBaseUrl(),
                    sampling != null ? sampling.getTemperature() : "N/A", sampling != null ? sampling.getTopP() : "N/A",
                    sampling != null ? sampling.getMaxRetries() : "N/A");
            modelMap.put("model", model.getName());
            modelMap.put("model_name", model.getName());

            if (sampling != null) {
                modelMap.put("temperature", sampling.getTemperature());
                modelMap.put("top_p", sampling.getTopP());
            }

            backendMap.put("provider", model.getProvider());
            backendMap.put("client_provider", model.getProvider());
            backendMap.put("apiKey", model.getApiKey());
            backendMap.put("api_key", model.getApiKey());
            backendMap.put("baseUrl", model.getBaseUrl());
            backendMap.put("apiBase", model.getBaseUrl());
            backendMap.put("api_base", model.getBaseUrl());
        }

        // 对齐 Python agent.py L131-134: [EDP-LLM-CONFIG] model_config_obj is None; sampling override SKIPPED
        if (model == null) {
            LOGGER.warn("[EDP-LLM-CONFIG] model config is null; LLM configuration SKIPPED");
        }

        List<String> skillDirs = (skillsDir != null && Files.exists(skillsDir))
                ? List.of(skillsDir.toString())
                : List.of();

        String skillMode = actrule != null && actrule.getSkillMode() != null ? actrule.getSkillMode() : "all";

        // 使用 agent-core 的 KV 存储（Redis）替代自定义 RedisTodoStore。
        // kvStoreConfig 为空时回落到默认 file 存储。
        Map<String, Object> kvStoreConfig = buildKvStoreConfig();
        String todoStorageType = !kvStoreConfig.isEmpty() ? "kv" : "file";
        LOGGER.info("[EDPA-DIAG] DeepAgent todoStorageType={}, kvStore={}",
                todoStorageType, !kvStoreConfig.isEmpty() ? "redis" : "none");

        return DeepAgentConfig.builder().systemPrompt(systemPrompt != null ? systemPrompt : "")
                .maxIterations(actrule != null && actrule.getMaxSteps() != null && actrule.getMaxSteps() > 0
                        ? actrule.getMaxSteps()
                        : 15)
                .enableTaskLoop(
                        actrule != null && actrule.getEnableTaskLoop() != null ? actrule.getEnableTaskLoop() : false)
                .enableTaskPlanning(true).skillDirectories(skillDirs).skillMode(skillMode).model(modelMap)
                .backend(backendMap).todoStorageType(todoStorageType).kvStoreConfig(kvStoreConfig).build();
    }

    /**
     * 从 RedisConfig 静态持有者获取 TodoRedisProperties，构建 agent-core 的 kvStoreConfig。
     *
     * <p>结构: {type: "redis", conf: {host, port, password, cluster}}。
     * Redis 未配置时返回空 Map，DeepAgent 回落到默认的 file 存储。</p>
     *
     * @return kvStoreConfig Map，或空 Map
     */
    private static Map<String, Object> buildKvStoreConfig() {
        TodoRedisProperties redisProps = RedisConfig.getRedisProperties();
        if (redisProps == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("host", redisProps.getHost());
        conf.put("port", redisProps.getPort());
        if (redisProps.getPassword() != null && !redisProps.getPassword().isBlank()) {
            conf.put("password", redisProps.getPassword());
        }
        String mode = redisProps.getMode() == null ? "single" : redisProps.getMode().toLowerCase();
        if ("cluster".equals(mode)) {
            conf.put("cluster", "true");
        } else if ("sentinel".equals(mode)) {
            LOGGER.warn("[EDPA-DIAG] Sentinel mode not supported by agent-core RedisKVStoreProvider, "
                    + "falling back to single mode");
        } else {
            LOGGER.debug("[EDPA-DIAG] Redis mode={} -> single mode config", mode);
        }

        // 创建带连接池的 UnifiedJedis，通过 redis_client 键传入 agent-core。
        // agent-core 的 RedisKVStoreProvider.resolveRedisClient() 检查 conf.get("redis_client")，
        // 如果存在则直接使用，不会创建裸 Jedis 连接（避免 SocketException 断连问题）。
        // 连接池配置参考 RedisJedisClientFactory：
        //   testOnBorrow=true（借用前 PING 验证）、testWhileIdle=true（空闲清理）
        Optional<Object> pooledClient = createPooledRedisClient(redisProps);
        if (pooledClient.isPresent()) {
            conf.put("redis_client", pooledClient.get());
            LOGGER.info("[EDPA-DIAG] Injected pooled Redis client (UnifiedJedis + PooledConnectionProvider), "
                    + "testOnBorrow=true, testWhileIdle=true, maxTotal=16, maxIdle=8, minIdle=1");
        }

        Map<String, Object> kvStoreConfig = new LinkedHashMap<>();
        kvStoreConfig.put("type", "redis");
        kvStoreConfig.put("conf", conf);
        return kvStoreConfig;
    }

    /**
     * 创建带连接池的 Redis 客户端（UnifiedJedis + PooledConnectionProvider）。
     *
     * <p>参考 RedisJedisClientFactory 的连接池配置：
     * <ul>
     *   <li>testOnBorrow=true — 借用连接前发送 PING 验证有效性，断连后自动创建新连接</li>
     *   <li>testWhileIdle=true — evictor 定期清理空闲失效连接</li>
     * </ul>
     *
     * @param redisProps Redis 连接配置
     * @return UnifiedJedis 实例的 Optional，创建失败返回 Optional.empty()（回退到 agent-core 默认的裸 Jedis）
     */
    private static Optional<Object> createPooledRedisClient(TodoRedisProperties redisProps) {
        try {
            String host = redisProps.getHost() != null ? redisProps.getHost().trim() : "localhost";
            int port = redisProps.getPort() > 0 ? redisProps.getPort() : 6379;
            String password = redisProps.getPassword();
            boolean hasPassword = password != null && !password.isBlank();

            // 连接池配置（参考 RedisJedisClientFactory.poolConfig()）
            org.apache.commons.pool2.impl.GenericObjectPoolConfig<redis.clients.jedis.Connection> poolConfig =
                    new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
            poolConfig.setMaxTotal(16);
            poolConfig.setMaxIdle(8);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);

            // Jedis 客户端配置（密码、数据库、超时）
            redis.clients.jedis.DefaultJedisClientConfig.Builder builder =
                    redis.clients.jedis.DefaultJedisClientConfig.builder();
            int timeoutMs = redisProps.getSocketTimeoutMs() > 0 ? redisProps.getSocketTimeoutMs() : 3000;
            builder.connectionTimeoutMillis(timeoutMs);
            builder.socketTimeoutMillis(timeoutMs);
            if (hasPassword) {
                builder.password(password);
            }
            int database = redisProps.getDatabase();
            if (database > 0) {
                builder.database(database);
            }
            redis.clients.jedis.JedisClientConfig clientConfig = builder.build();

            // 创建 PooledConnectionProvider + UnifiedJedis
            redis.clients.jedis.HostAndPort hostAndPort = new redis.clients.jedis.HostAndPort(host, port);
            redis.clients.jedis.providers.PooledConnectionProvider provider =
                    new redis.clients.jedis.providers.PooledConnectionProvider(hostAndPort, clientConfig, poolConfig);
            return Optional.of(new redis.clients.jedis.UnifiedJedis(provider));
        } catch (JedisConnectionException e) {
            LOGGER.warn("[EDPA-DIAG] Failed to create pooled Redis client, falling back to default: {}",
                    e.getMessage());
            return Optional.empty();
        }
    }

    private static void registerSkills(DeepAgent deepAgent, Path skillsDir, String agentName) {
        if (skillsDir == null || !Files.exists(skillsDir)) {
            LOGGER.info("Skill load skipped: skills directory not found or not configured");
            return;
        }
        ensureSkillSysOperationId(deepAgent, agentName);
        deepAgent.getAgent().registerSkill(skillsDir.toString());
        boolean hasSkill = deepAgent.getAgent().getSkillUtil() != null
                && deepAgent.getAgent().getSkillUtil().hasSkill();
        int skillCount = hasSkill ? deepAgent.getAgent().getSkillUtil().getSkillManager().count() : 0;
        List<String> skillNames = hasSkill
                ? deepAgent.getAgent().getSkillUtil().getSkillManager().getNames()
                : List.of();
        LOGGER.info("Skill load completed: hasSkill={}, skillCount={}, skillNames={}, dir={}", hasSkill, skillCount,
                skillNames, skillsDir);
    }

    private static void ensureSkillSysOperationId(DeepAgent deepAgent, String agentName) {
        Object config = deepAgent.getAgent().getConfig();
        if (config instanceof ReActAgentConfig reactConfig && reactConfig.getSysOperationId() == null) {
            reactConfig.setSysOperationId(agentName);
        }
    }

    // ===== 诊断方法 =====

    /**
     * Gets the deep agent.
     *
     * @return the result
     */
    public DeepAgent getDeepAgent() {
        return deepAgent;
    }

    /**
     * Gets the edp config.
     *
     * @return the result
     */
    public EdpConfig getEdpConfig() {
        return edpConfig;
    }

    /**
     * Gets the governance config.
     *
     * @return the result
     */
    public GovernanceConfig getGovernanceConfig() {
        return governanceConfig;
    }

    /**
     * Gets the spring boot config.
     *
     * @return the result
     */
    public EdpaSpringBootConfig getSpringBootConfig() {
        return springBootConfig;
    }

    /**
     * Gets the scenario home path.
     *
     * @return the result
     */
    public Path getScenarioHomePath() {
        return scenarioHomePath;
    }
}
