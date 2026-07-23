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
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.huawei.ascend.edp.enhancer.EdpaAgentEnhancer;
import com.huawei.ascend.edp.rail.VersatileInterruptRail;
import com.huawei.ascend.edp.rail.VersatileInterruptRail.VersatilePassthroughBuffer;
import com.huawei.ascend.edp.stream.PlanrulePromptBuilder;
import com.huawei.ascend.edp.stream.QueryChunkFormatAdapter;
import com.huawei.ascend.edp.stream.SkillScriptsCollector;
import com.huawei.ascend.edp.todo.RedisTodoStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.interaction.InteractiveInput;
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
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EDPAgent 运行时适配器（适配版，Phase 2 合并后）。
 *
 * <p>继承 {@link JiuwenCoreAgentExtHandler}，接入 agent-runtime-java 的 A2A 执行链路，
 * 并自动获得 RemoteA2aToolInstaller 的远程工具注入能力。</p>
 *
 * <p>Phase 2 核心变化：</p>
 * <ul>
 *     <li>使用 {@link EdpaSpringBootConfig}（合并了原 EdpAgentProperties）统一获取所有配置。</li>
 *     <li>model/versatile/mcpsse 配置从 EdpaSpringBootConfig 嵌套结构获取，
 *         不再使用 EdpAgentConfig.Model/Versatile 或 EnvOverrides。</li>
 *     <li>调用完整10参数版 {@link EdpaAgentEnhancer#enhance}，
 *         包含 EdpaTodoRail/EdpaEventRail/ScriptsRail/EdpaToolRegistry 等全部业务增强。</li>
 *     <li>恢复 {@link EdpConfigValidator#validateModelConfig} 和
 *         {@link EdpConfigValidator#validateVersatileUrl} 校验（Phase 1注释掉的）。</li>
 *     <li>Versatile续流采用A2A协议栈透明转发（方案A），VersatileInterruptRail 传入 VersatileConfig。</li>
 * </ul>
 *
 * <p><b>排查指引（现场联调 / 问题定界定位）：</b></p>
 * <pre>
 *   grep "[EDP-LLM-EMPTY]"      → 快速定位 LLM 空 answer
 *   grep "[EDP-LLM-CONFIG]"     → 验证 LLM model/sampling 配置覆盖
 *   grep "[EDPA-DIAG]"          → 事件发射诊断 + 续流结果
 *   grep "[EDP-SANDBOX]"        → 沙箱初始化/执行诊断
 *   grep "EdpaExtHandler streamQuery" → 请求入口（首轮 vs 续轮）
 *   grep "Versatile continuation"     → 续流结果追踪
 * </pre>
 *
 * @since 2024-01-01
 *
 */

public class EdpaExtHandler extends JiuwenCoreAgentExtHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaExtHandler.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    private VersatilePassthroughBuffer versatilePassthroughBuffer;

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
         * Versatile passthrough 缓冲
         */
        private VersatilePassthroughBuffer versatilePassthroughBuffer;

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
         * Gets the versatile passthrough buffer.
         *
         * @return the result
         */
        public VersatilePassthroughBuffer getVersatilePassthroughBuffer() {
            return versatilePassthroughBuffer;
        }

        /**
         * Sets the versatile passthrough buffer.
         *
         * @param versatilePassthroughBuffer the versatilePassthroughBuffer value
         */
        public void setVersatilePassthroughBuffer(VersatilePassthroughBuffer versatilePassthroughBuffer) {
            this.versatilePassthroughBuffer = versatilePassthroughBuffer;
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
     * @param config EDPAgent 合并后配置（含 scenarioHome/model/versatile/mcpsse）
     * @param redisTodoStore Redis Todo 存储（可为null：未启用Redis时回落文件/缓存）
     * @param agentName Agent 名称（从 openjiuwen.service.a2a.agent-name 配置读取）
     * @return InitResult 包含真实 agent 实例和初始化产物
     *
     * @param decoratedSandboxClient the decoratedSandboxClient value
     */

    public static InitResult performInit(EdpaSpringBootConfig config, RedisTodoStore redisTodoStore, String agentName,
            SandboxClient decoratedSandboxClient) {
        LOGGER.info("EdpaExtHandler performInit start (Phase 2)");
        InitResult result = new InitResult();
        result.setSpringBootConfig(config);
        result.setAgentConfig(new EdpAgentConfig());
        result.setEdpConfig(new EdpConfig());
        Path yamlDir = Path.of("src/main/resources").toAbsolutePath().normalize();
        // 第三步：解析 scenarioHome 路径。
        String scenarioHome = config.getScenarioHome();
        if (scenarioHome != null && !scenarioHome.isBlank()) {
            result.setScenarioHomePath(Path.of(scenarioHome).toAbsolutePath().normalize());
            LOGGER.info("Scenario home resolved: {}", result.getScenarioHomePath());
        }

        // 第四步：加载 Governance 配置。
        Path governancePath = yamlDir.resolve("governance").toAbsolutePath().normalize();
        if (result.getScenarioHomePath() != null) {
            governancePath = result.getScenarioHomePath().resolve("governance").toAbsolutePath().normalize();
        }
        if (Files.exists(governancePath)) {
            try {
                result.setGovernanceConfig(GovernanceConfigLoader.load(governancePath));
                LOGGER.info("Governance loaded from {}", governancePath);
            } catch (IllegalStateException e) {
                LOGGER.warn("Failed to load governance config: {}", e.getMessage());
            }
        } else {
            LOGGER.info("Governance loaded from framework only (no scenarioHome)");
        }

        // 第五步：配置校验 fail-fast。
        EdpConfigValidator.validateModelConfig(config.getModel());
        EdpConfigValidator.validateVersatileUrl(config.getVersatile());
        if (result.getScenarioHomePath() != null) {
            EdpConfigValidator.validateScenarioConfig(result.getScenarioHomePath());
        }

        // 第六步：从 Governance actrule 加载 Todo 数据层。
        ActRuleConfig actrule = result.getGovernanceConfig() != null
                ? result.getGovernanceConfig().getActrule() : null;
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

        // 第七步：按 Governance 的 planrule 拼接系统提示词。
        String systemPrompt = "";
        if (result.getGovernanceConfig() != null && result.getGovernanceConfig().getPlanrule() != null) {
            systemPrompt = PlanrulePromptBuilder.buildSystemPromptFragment(result.getGovernanceConfig().getPlanrule());
            LOGGER.info("System prompt built from PlanrulePromptBuilder, length={}", systemPrompt.length());
        } else if (result.getAgentConfig().getPrompt() != null) {
            systemPrompt = result.getAgentConfig().getPrompt().getSystem();
        } else {
            LOGGER.info("No system prompt source available, using empty default");
        }

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
        SysScriptsConfig sysScriptsConfig = new SysScriptsConfig();
        Path frameworkScriptsPath = yamlDir.resolve("governance").resolve("scriptconfig.yaml").toAbsolutePath()
                .normalize();
        if (Files.exists(frameworkScriptsPath)) {
            sysScriptsConfig.load(frameworkScriptsPath.toString());
            LOGGER.info("Framework scripts loaded from {}", frameworkScriptsPath);
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

        // 第十二步：注册 EDPAgent 内置业务工具和业务 Rails（13参数版，含沙箱）。
        result.setVersatilePassthroughBuffer(new VersatilePassthroughBuffer());

        // --- 沙箱特性：创建SysOperation双模式门面 ---
        if (config.getSandbox() != null && config.getSandbox().isEnabled()) {
            result.setSandboxGatewayConfig(buildSandboxGatewayConfig(config.getSandbox()));
        }
        result.setSysOperation(createSysOperationIfNeeded(config, result.getSandboxGatewayConfig()).orElse(null));
        result.setDecoratedSandboxClient(decoratedSandboxClient);

        // --- enhance调用合并 ---
        EdpaAgentEnhancer.EnhanceContext enhanceCtx = new EdpaAgentEnhancer.EnhanceContext();
        enhanceCtx.edpConfig = result.getEdpConfig();
        enhanceCtx.springBootConfig = config;
        enhanceCtx.actrule = actrule;
        enhanceCtx.toolDataChannel = new ToolDataChannel();
        enhanceCtx.skillsDir = skillsDir;
        enhanceCtx.passthroughBuffer = result.getVersatilePassthroughBuffer();
        enhanceCtx.deepAgent = result.getDeepAgent();
        enhanceCtx.edpaTodolist = edpaTodolist;
        enhanceCtx.scripts = sysScriptsConfig;
        enhanceCtx.agentName = agentName;
        enhanceCtx.sysOp = result.getSysOperation();
        enhanceCtx.gatewayConfig = result.getSandboxGatewayConfig();
        enhanceCtx.decoratedSandboxClient = decoratedSandboxClient;
        EdpaAgentEnhancer.enhance(result.getDeepAgent(), enhanceCtx);

        // 第十三步：强制完成 DeepAgent 初始化。
        result.getDeepAgent().ensureInitialized();

        // 获取真实 agent 实例
        result.setAgentInstance(result.getDeepAgent().getAgent());

        LOGGER.info("EdpaExtHandler performInit completed, agentId={}, deepAgent initialized={}, scenarioHome={}",
                agentName, result.getDeepAgent().isInitialized(), result.getScenarioHomePath());
        return result;
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
        this.versatilePassthroughBuffer = result.getVersatilePassthroughBuffer();
        this.scenarioHomePath = result.getScenarioHomePath();
    }

    // ===== 适配版 SPI 覆写方法 =====

    /**
     * 适配版 SPI：流式查询，分两种路径：
     * <ul>
     *     <li>Versatile 续流：conversationId 有续流标记时，从 passthroughNodes 提取输入并继续调用</li>
     *     <li>首轮请求：无续流标记时，走标准 streamQueryWithPassthrough 路径</li>
     * </ul>
     * 对齐 Python agent.py L894-917: 首轮 vs 续轮判断。
     *
     */

    @Override
    /**
     * Stream query.
     *
     * @param request the request value
     * @param observer the observer value
     */
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String conversationId = request.getConversationId();
        Map<String, Object> input = extractRequestInputs(request);

        Map<String, Object> continuationInputs = extractVersatileContinuationInputs(input);
        if (!continuationInputs.isEmpty()) {
            LOGGER.info("EdpaExtHandler streamQuery: versatile continuation conversationId={}", conversationId);
            QueryStreamObserver formatAdapter = new QueryChunkFormatAdapter(observer, conversationId);

            VersatileInterruptRail rail = new VersatileInterruptRail(edpConfig != null ? edpConfig : new EdpConfig(),
                    springBootConfig != null ? springBootConfig.getVersatile() : null, new ToolDataChannel(),
                    versatilePassthroughBuffer);
            Map<String, Object> result = rail.invokeWithInputs(continuationInputs, conversationId);

            if (isTerminalVersatileResult(result)) {
                String interruptId = versatilePassthroughBuffer.pollInterruptId(conversationId).orElse(null);
                Object resumeInput = versatileToolResumeInput(conversationId, interruptId, result);
                ServeRequest resumeRequest = buildResumeRequest(request, resumeInput, conversationId);
                drainPassthroughNodesToObserver(conversationId, formatAdapter);
                super.streamQuery(resumeRequest, formatAdapter);
                return;
            }

            drainPassthroughNodesToObserver(conversationId, formatAdapter);
            pushVersatileContinuationResult(conversationId, result, observer);
            return;
        }

        // 对齐 Python agent.py L906: 确认是首轮请求：conv_id=..., is_resume=false
        LOGGER.info("EdpaExtHandler streamQuery: conversationId={}, isVersatileContinuation=false", conversationId);
        streamQueryWithPassthrough(request, observer, conversationId);
    }

    /**
     * 适配版 SPI：同步查询。委托给父类。
     *
     */

    @Override
    /**
     * Query.
     *
     * @param request the request value
     * @return the result
     */
    public QueryResponse query(ServeRequest request) {
        return super.query(request);
    }

    // ===== Versatile passthrough 间插方法 =====

    private void drainPassthroughNodesToObserver(String conversationId, QueryStreamObserver observer) {
        Optional<String> node = versatilePassthroughBuffer.poll(conversationId);
        while (node.isPresent()) {
            if (observer.isCancelled()) {
                return;
            }
            String displayText = extractPassthroughDisplayText(node.get()).orElse(null);
            if (displayText != null && !displayText.isBlank()) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, displayText));
            }
            node = versatilePassthroughBuffer.poll(conversationId);
        }
    }

    private Optional<String> extractPassthroughDisplayText(String nodeJson) {
        Map<String, Object> eventMap = parseJsonObject(nodeJson);
        if (eventMap == null || eventMap.isEmpty()) {
            return Optional.empty();
        }

        String type = String.valueOf(eventMap.getOrDefault("type", ""));
        if ("answer".equals(type)) {
            LOGGER.debug("extractPassthroughDisplayText: discarded answer node");
            return Optional.empty();
        }

        Map<String, Object> innerData = resolveInnerData(eventMap);
        if (innerData == null || innerData.isEmpty()) {
            return Optional.empty();
        }

        String nodeType = String.valueOf(innerData.getOrDefault("node_type", ""));
        switch (nodeType) {
            case "LLM" :
            case "Custom" :
                String text = String.valueOf(innerData.getOrDefault("text", ""));
                return text != null && !text.isBlank() ? Optional.of(text) : Optional.empty();
            case "Start" :
            case "End" :
            case "QA" :
                LOGGER.debug("extractPassthroughDisplayText: discarded {} node", nodeType);
                return Optional.empty();
            default :
                LOGGER.debug("extractPassthroughDisplayText: discarded unknown node_type={}", nodeType);
                return Optional.empty();
        }
    }

    private Map<String, Object> resolveInnerData(Map<String, Object> eventMap) {
        Object dataObj = eventMap.get("data");
        if (dataObj instanceof Map<?, ?> dataMap) {
            return normalizeStringMap(dataMap);
        }
        if (dataObj instanceof String dataStr && !dataStr.isBlank()) {
            Map<String, Object> parsed = parseJsonObject(dataStr);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        }
        return eventMap;
    }

    // ===== Versatile 续流辅助方法 =====

    private Map<String, Object> extractRequestInputs(ServeRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        String query = request.lastUserQuery();
        if (query != null && !query.isBlank()) {
            input.put("query", query);
        }
        if (request.getConversationId() != null) {
            input.put("conversation_id", request.getConversationId());
        }
        if (request.getMetadata() != null) {
            input.put("metadata", request.getMetadata());
        }
        if (request.getMessages() != null) {
            input.put("messages", request.getMessages());
        }
        return input;
    }

    /**
     * 从 passthroughNodes 提取 Versatile 续流输入。
     * 数据流：Versatile 返回 passthroughNodes → 提取 query + body → 构建续流请求。
     * 对齐 Python agent.py L1028-1046: pending_delegate 解析 + INTERRUPTION_KEY 检测。
     *
     * @param input 原始 input Map（包含 passthroughNodes）
     * @return 续流请求 Map，解析失败返回 null
     *
     */

    private Map<String, Object> extractVersatileContinuationInputs(Map<String, Object> input) {
        Object queryObj = input.get("query");
        if (!(queryObj instanceof String text) || text.isBlank()) {
            LOGGER.warn("Versatile continuation input query is blank or non-string");
            return Collections.emptyMap();
        }
        Map<String, Object> body = parseJsonObject(text);
        if (body.isEmpty()) {
            LOGGER.warn("Versatile continuation input JSON parse returned empty");
            return Collections.emptyMap();
        }
        Object inputs = body.get("inputs");
        Map<String, Object> normalized = inputs instanceof Map<?, ?> inputsMap
                ? normalizeStringMap(inputsMap)
                : normalizeStringMap(body);
        return isVersatileMenuConfirmation(normalized) ? normalized : Collections.emptyMap();
    }

    private Map<String, Object> parseJsonObject(String text) {
        try {
            return OBJECT_MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            // 降级说明：JSON 解析失败，返回空 Map 兜底
            LOGGER.warn("[EDPA-DIAG] parseJsonObject failed, returning empty map: err={}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> normalizeStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private boolean isVersatileMenuConfirmation(Map<String, Object> inputs) {
        return inputs != null && !inputs.isEmpty() && inputs.containsKey("menu_type")
                && inputs.containsKey("menu_confirm");
    }

    private boolean isTerminalVersatileResult(Map<String, Object> result) {
        return result != null && "completed".equals(String.valueOf(result.get("status")));
    }

    private Object versatileToolResumeInput(String conversationId, String interruptId, Map<String, Object> result) {
        InteractiveInput interactiveInput = new InteractiveInput();
        interactiveInput.update(interruptId != null && !interruptId.isBlank() ? interruptId : "call_versatile",
                toJson(result));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", interactiveInput);
        input.put("conversation_id", conversationId);
        return input;
    }

    private ServeRequest buildResumeRequest(ServeRequest original, Object resumeInput, String conversationId) {
        return original;
    }

    /**
     * 推送 Versatile 续流结果到客户端。
     * 根据 result.status 走不同分支：
     * <ul>
     *     <li>input_required → 中断态，发送 INTERRUPT chunk</li>
     *     <li>failed → 失败态，发送 ERROR chunk</li>
     *     <li>其他（completed）→ 正常输出 chunk</li>
     * </ul>
     * 对齐 Python agent.py L894: Cascade 续轮结果推送。
     *
     * @param conversationId the conversationId value
     * @param result the result value
     * @param observer the observer value
     */

    private void pushVersatileContinuationResult(String conversationId, Map<String, Object> result,
            QueryStreamObserver observer) {
        String status = result != null ? String.valueOf(result.get("status")) : "failed";
        LOGGER.info("[EDPA-DIAG] Versatile continuation result: conversationId={}, status={}", conversationId, status);
        if ("input_required".equals(status)) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, result));
            observer.onComplete();
            return;
        }
        if ("failed".equals(status)) {
            LOGGER.warn("[EDPA-DIAG] Versatile continuation failed: conversationId={}", conversationId);
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put("type", "error");
            errorData.put("error", "VERSATILE_CONTINUATION_FAILED");
            errorData.put("content", result != null ? result.get("content") : "adapter call failed");
            observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, errorData));
            observer.onError(new IllegalStateException("Versatile continuation failed"));
            return;
        }
        Object content = result != null ? result.get("content") : "";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", "assistant");
        data.put("content", content == null ? "" : String.valueOf(content));
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, data));
        observer.onComplete();
    }

    private void streamQueryWithPassthrough(ServeRequest request, QueryStreamObserver observer, String conversationId) {
        QueryStreamObserver formatAdapter = new QueryChunkFormatAdapter(observer, conversationId);

        QueryStreamObserver wrappedObserver = new QueryStreamObserver() {
            @Override
            /**
             * On next.
             *
             * @param chunk the chunk value
             */
            public void onNext(QueryChunk chunk) {
                drainPassthroughNodesToObserver(conversationId, formatAdapter);
                formatAdapter.onNext(chunk);
            }

            @Override
            /**
             * On error.
             *
             * @param error the error value
             */
            public void onError(Throwable error) {
                formatAdapter.onError(error);
            }

            @Override
            /**
             * On complete.
             */
            public void onComplete() {
                drainPassthroughNodesToObserver(conversationId, formatAdapter);
                formatAdapter.onComplete();
            }

            @Override
            /**
             * Checks whether cancelled.
             *
             * @return the result
             */
            public boolean isCancelled() {
                return formatAdapter.isCancelled();
            }
        };

        super.streamQuery(request, wrappedObserver);
        versatilePassthroughBuffer.clear(conversationId);
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize object", e);
        }
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

        return DeepAgentConfig.builder().systemPrompt(systemPrompt != null ? systemPrompt : "")
                .maxIterations(actrule != null && actrule.getMaxSteps() != null && actrule.getMaxSteps() > 0
                        ? actrule.getMaxSteps()
                        : 15)
                .enableTaskLoop(
                        actrule != null && actrule.getEnableTaskLoop() != null ? actrule.getEnableTaskLoop() : false)
                .enableTaskPlanning(true).skillDirectories(skillDirs).skillMode(skillMode).model(modelMap)
                .backend(backendMap).build();
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
