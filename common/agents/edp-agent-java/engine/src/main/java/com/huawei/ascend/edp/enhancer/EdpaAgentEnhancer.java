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

package com.huawei.ascend.edp.enhancer;

import com.huawei.ascend.edp.channel.ToolDataChannel;
import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.EdpConfig;
import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.config.EdpaTodolist;
import com.huawei.ascend.edp.config.RedisConfig;
import com.huawei.ascend.edp.config.SysScriptsConfig;
import com.huawei.ascend.edp.rail.AskUserTemplateRail;
import com.huawei.ascend.edp.rail.CancelRail;
import com.huawei.ascend.edp.rail.EdpaEventRail;
import com.huawei.ascend.edp.rail.EdpaTodoRail;
import com.huawei.ascend.edp.rail.ExecutionLimitRail;
import com.huawei.ascend.edp.rail.LogRail;
import com.huawei.ascend.edp.rail.McpInterruptRail;
import com.huawei.ascend.edp.rail.SandboxInterruptRail;
import com.huawei.ascend.edp.rail.ScriptsRail;
import com.huawei.ascend.edp.rail.VersatileInterruptRail;
import com.huawei.ascend.edp.rail.VersatileInterruptRail.VersatilePassthroughBuffer;
import com.huawei.ascend.edp.todo.RedisTodoStore;
import com.huawei.ascend.edp.tools.EdpaBusinessTools;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.sysop.SysOperation;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.harness.deep_agent.DeepAgent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * EDPAgent 业务增强器。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>从 tools 包获取 EDPAgent spike 阶段内置业务工具。</li>
 *     <li>集中定义并构造 EDPAgent spike 阶段业务 Rails。</li>
 *     <li>把业务工具和 Rails 注册到 DeepAgent，使 A2A 请求执行时具备业务能力。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #enhance(DeepAgent, EdpConfig)}：一次性注册业务工具和业务 Rails。</li>
 *     <li>{@link #buildBusinessTools(EdpConfig, ActRuleConfig)}：构造内置业务工具列表，供注册和测试复用。</li>
 *     <li>{@link #buildBusinessRails(EdpConfig)}：构造业务 Rails 列表，供注册和运行时复用。</li>
 * </ul>
 *
 * @since 2024-01-01
 *
 */

public class EdpaAgentEnhancer {
    /**
     * MCP 沙箱调用工具名。
     *
     */

    public static final String TOOL_CALL_MCP = EdpaBusinessTools.TOOL_CALL_MCP;

    /**
     * Versatile Agent 委托调用工具名。
     *
     */

    public static final String TOOL_CALL_VERSATILE = EdpaBusinessTools.TOOL_CALL_VERSATILE;

    /**
     * 用户追问工具名。该工具需要触发 OpenJiuwen interrupt，而不是普通工具返回。
     *
     */

    public static final String TOOL_ENHANCED_ASK_USER = EdpaBusinessTools.TOOL_ENHANCED_ASK_USER;

    /**
     * 任务取消工具名。
     *
     */

    public static final String TOOL_CANCEL_TASK = EdpaBusinessTools.TOOL_CANCEL_TASK;

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaAgentEnhancer.class);

    /**
     * 增强 DeepAgent。
     *
     * <p>作用：把 EDPAgent spike 阶段已经迁移的业务工具和业务 Rails 注册到 DeepAgent。</p>
     *
     * @param agent 待增强的 DeepAgent 实例，不能为空
     * @param edpConfig EDP 专有配置，提供工具 Schema 和 Rail 行为需要的业务参数
     *
     */

    public static void enhance(DeepAgent agent, EdpConfig edpConfig) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setToolDataChannel(new ToolDataChannel());
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        enhance(agent, ctx);
    }

    /**
     * Enhances the agent with EDP configuration.
     *
     * @param agent the agent value
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     */
    public static void enhance(DeepAgent agent, EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(new ToolDataChannel());
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        enhance(agent, ctx);
    }

    /**
     * Enhances the agent with EDP configuration.
     *
     * @param agent the agent value
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @param toolDataChannel the toolDataChannel value
     */
    public static void enhance(DeepAgent agent, EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig,
            ToolDataChannel toolDataChannel) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(toolDataChannel);
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        enhance(agent, ctx);
    }

    /**
     * Enhances the agent with EDP configuration.
     *
     * @param agent the agent value
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @param toolDataChannel the toolDataChannel value
     * @param skillsDir the skillsDir value
     */
    public static void enhance(DeepAgent agent, EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig,
            ToolDataChannel toolDataChannel, Path skillsDir) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(toolDataChannel);
        ctx.setSkillsDir(skillsDir);
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        enhance(agent, ctx);
    }

    /**
     * 增强 DeepAgent（完整参数版，含话术配置和治理装饰 SandboxClient）。
     *
     * @param agent 待增强的 DeepAgent 实例，不能为空
     * @param ctx 配置上下文，承载工具数据通道、技能目录、话术配置、沙箱配置等
     *
     */

    public static void enhance(DeepAgent agent, EnhanceContext ctx) {
        // 关键判断：DeepAgent 是注册工具和 Rail 的目标对象，缺失时直接失败，避免静默启动。
        if (agent == null) {
            throw new IllegalArgumentException("DeepAgent instance must not be null");
        }
        LOGGER.info("EdpaAgentEnhancer.enhance() start, edpConfig scope={}, sandbox={}",
                ctx.getEdpConfig().getScope() != null ? ctx.getEdpConfig().getScope().getAllowed() : "null",
                ctx.getSysOp() != null ? ctx.getSysOp().getMode() : "disabled");

        // 先注册工具，确保模型工具列表和后续 Rail 拦截逻辑具备目标工具。
        registerBusinessTools(agent, ctx.getEdpConfig(), ctx.getActrule());

        // deepAgent 缺省回退为 agent 自身，确保 EdpaTodoRail/EdpaEventRail 能访问 workspace。
        if (ctx.getDeepAgent() == null) {
            ctx.setDeepAgent(agent);
        }

        // 再注册 Rails，确保模型调用、工具调用、记忆、日志等回调进入执行链路。
        registerBusinessRails(agent, ctx);

        LOGGER.info("EdpaAgentEnhancer.enhance() completed");
    }

    /**
     * 构造 EDPAgent 内置业务工具列表。
     *
     * <p>作用：委托 tools 包构造 Python EDPAgent 中已识别的核心工具。</p>
     *
     * @param edpConfig EDP 专有配置，用于动态生成 lite_todo_write 的 step_id 枚举
     * @return 工具列表，包含 lite_todo_write、call_mcp、call_versatile、ask_user、cancel_task
     *
     * @param actrule description
     *
     */

    public static List<Tool> buildBusinessTools(EdpConfig edpConfig, ActRuleConfig actrule) {
        return EdpaBusinessTools.build(edpConfig, actrule);
    }

    /**
     * 构造 EDPAgent 业务 Rails。
     *
     * <p>作用：为模型调用、工具调用和会话执行过程挂接 EDPAgent 业务回调。</p>
     *
     * @param edpConfig EDP 专有配置，供各 Rail 读取 scope、limits、话术等配置
     * @return Rail 列表，注册顺序即当前 spike 阶段的业务回调顺序
     *
     */

    public static List<AgentRail> buildBusinessRails(EdpConfig edpConfig) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setToolDataChannel(new ToolDataChannel());
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        return buildBusinessRails(ctx);
    }

    /**
     * Builds the business rails for the agent.
     *
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @return the result
     */
    public static List<AgentRail> buildBusinessRails(EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(new ToolDataChannel());
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        return buildBusinessRails(ctx);
    }

    /**
     * Builds the business rails for the agent.
     *
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @param toolDataChannel the toolDataChannel value
     * @return the result
     */
    public static List<AgentRail> buildBusinessRails(EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig,
            ToolDataChannel toolDataChannel) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(toolDataChannel);
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        return buildBusinessRails(ctx);
    }

    /**
     * Builds the business rails for the agent.
     *
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @param toolDataChannel the toolDataChannel value
     * @param skillsDir the skillsDir value
     * @return the result
     */
    public static List<AgentRail> buildBusinessRails(EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig,
            ToolDataChannel toolDataChannel, Path skillsDir) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(toolDataChannel);
        ctx.setSkillsDir(skillsDir);
        ctx.setPassthroughBuffer(new VersatilePassthroughBuffer());
        ctx.setAgentName("EDPAgent");
        return buildBusinessRails(ctx);
    }

    /**
     * @see #buildBusinessRails(EnhanceContext)
     *
     * @param edpConfig the edpConfig value
     * @param springBootConfig the springBootConfig value
     * @param toolDataChannel the toolDataChannel value
     * @param skillsDir the skillsDir value
     * @param passthroughBuffer the passthroughBuffer value
     * @return the result
     */
    public static List<AgentRail> buildBusinessRails(EdpConfig edpConfig, EdpaSpringBootConfig springBootConfig,
            ToolDataChannel toolDataChannel, Path skillsDir, VersatilePassthroughBuffer passthroughBuffer) {
        EnhanceContext ctx = new EnhanceContext();
        ctx.setEdpConfig(edpConfig);
        ctx.setSpringBootConfig(springBootConfig);
        ctx.setToolDataChannel(toolDataChannel);
        ctx.setSkillsDir(skillsDir);
        ctx.setPassthroughBuffer(passthroughBuffer);
        ctx.setAgentName("EDPAgent");
        return buildBusinessRails(ctx);
    }

    /**
     * 构造业务 Rails（完整参数版，支持注入 RedisTodoStore 和治理装饰 SandboxClient）。
     *
     * @param ctx 配置上下文，承载 EDP 配置、技能目录、话术配置、沙箱配置等
     * @return Rail 列表
     *
     */

    public static List<AgentRail> buildBusinessRails(EnhanceContext ctx) {
        ToolDataChannel sharedChannel =
                ctx.getToolDataChannel() != null ? ctx.getToolDataChannel() : new ToolDataChannel();
        VersatilePassthroughBuffer sharedPassthroughBuffer = ctx.getPassthroughBuffer() != null
                ? ctx.getPassthroughBuffer()
                : new VersatilePassthroughBuffer();
        List<AgentRail> rails = new ArrayList<>();

        // 取消类 Rail 优先注册，使取消信号尽早生效。
        rails.add(new CancelRail(ctx.getEdpConfig()));

        // 增强 Rail（catalog_id 补全 + 依赖闭环 + PLAN_FIRST 守卫），仅当 todolist 非空时注册。
        if (ctx.getDeepAgent() != null && ctx.getEdpaTodolist() != null) {
            rails.add(new EdpaTodoRail(ctx.getDeepAgent(), ctx.getEdpaTodolist(), ctx.getRedisTodoStore(),
                    ctx.getActrule()));
        }

        // 执行限制 Rail 负责阻断失控循环。
        rails.add(new ExecutionLimitRail(ctx.getActrule()));

        // 沙箱中断 Rail（SANDBOX模式拦截call_mcp，LOCAL模式放行给McpInterruptRail处理）
        if (ctx.getSysOp() != null && ctx.getSpringBootConfig() != null
                && ctx.getSpringBootConfig().getSandbox() != null
                && ctx.getSpringBootConfig().getSandbox().isEnabled()) {
            rails.add(new SandboxInterruptRail(ctx.getSysOp(), ctx.getSpringBootConfig().getSandbox(),
                    ctx.getGatewayConfig(), ctx.getDecoratedSandboxClient()));
            LOGGER.info("Registered SandboxInterruptRail (sandbox mode={}, governed={})", ctx.getSysOp().getMode(),
                    ctx.getDecoratedSandboxClient() != null);
        }

        // MCP / VA / ask_user Rail 负责工具调用前后的业务中断和参数增强。
        // SANDBOX 模式下传递 skillDeployPath，使 McpInterruptRail 在 SANDBOX 分支使用 cwd + 相对路径。
        String skillDeployPath = (ctx.getSpringBootConfig() != null
                && ctx.getSpringBootConfig().getSandbox() != null
                && ctx.getSpringBootConfig().getSandbox().isEnabled())
                        ? ctx.getSpringBootConfig().getSandbox().getSkillDeployPath()
                        : null;
        rails.add(new McpInterruptRail(ctx.getEdpConfig(), sharedChannel, ctx.getSkillsDir(),
                ctx.getSpringBootConfig(), ctx.getAgentName(), ctx.getSysOp(), skillDeployPath,
                ctx.getDecoratedSandboxClient()));
        rails.add(
                new VersatileInterruptRail(ctx.getEdpConfig(),
                        ctx.getSpringBootConfig() != null ? ctx.getSpringBootConfig().getVersatile() : null,
                        sharedChannel, sharedPassthroughBuffer, ctx.getSkillsDir(), ctx.getScripts(),
                        ctx.getAgentName(), ctx.getSysOp(), skillDeployPath, ctx.getDecoratedSandboxClient()));
        rails.add(new AskUserTemplateRail(ctx.getEdpConfig(), ctx.getScripts()));

        // Log Rail 负责观测日志。
        rails.add(new LogRail(ctx.getEdpConfig()));

        // 思维链事件发射 Rail（todo/tool/think/final_answer 事件流），需要 deepAgent。
        if (ctx.getDeepAgent() != null) {
            rails.add(new EdpaEventRail(ctx.getDeepAgent(), ctx.getScripts(), ctx.getRedisTodoStore()));
        }

        // 话术出口 Rail（B 面：首轮/业务话术/出口/合规/Prompt）。
        rails.add(new ScriptsRail(ctx.getScripts(), ctx.getEdpConfig()));

        return rails;
    }

    /**
     * 注册业务工具到 DeepAgent。
     *
     * @param agent DeepAgent 实例
     * @param edpConfig EDP 专有配置
     * @param actrule 行为治理配置（含 allowed_tools，驱动工具注册）
     *
     */

    private static void registerBusinessTools(DeepAgent agent, EdpConfig edpConfig, ActRuleConfig actrule) {
        List<Tool> tools = buildBusinessTools(edpConfig, actrule);
        for (Tool tool : tools) {
            agent.registerHarnessTool(tool);
            LOGGER.info("Registered business tool: {}", tool.getCard().getName());
        }
    }

    /**
     * 注册业务 Rails 到 DeepAgent 底层 BaseAgent。
     *
     * @param agent DeepAgent 实例
     * @param ctx 配置上下文，承载 EDP 配置、技能目录、话术配置、沙箱配置等
     *
     */

    private static void registerBusinessRails(DeepAgent agent, EnhanceContext ctx) {
        // 从 Spring 容器获取 RedisTodoStore（非 Spring 管理类通过静态持有访问）
        ctx.setRedisTodoStore(RedisConfig.getRedisTodoStore());
        List<AgentRail> rails = buildBusinessRails(ctx);
        for (AgentRail rail : rails) {
            // Rail 注册在底层 BaseAgent 上，ReAct 执行循环会按事件和优先级触发回调。
            agent.getAgent().registerRail(rail);
            LOGGER.info("Registered business rail: {} (priority={})", rail.getClass().getSimpleName(),
                    rail.getPriority());
        }
    }

    /**
     * enhance / buildBusinessRails 的参数对象，将 14 个配置参数收敛为单一对象，保证方法参数不超过 5 个。
     *
     * @since 2024-01-01
     *
     */

    public static final class EnhanceContext {
        /**
         * EDP 专有配置。
         */
        private EdpConfig edpConfig;
        /**
         * model / versatile 配置。
         */
        private EdpaSpringBootConfig springBootConfig;
        /**
         * 行为治理配置（含 allowed_tools，驱动工具注册）。
         */
        private ActRuleConfig actrule;
        /**
         * 工具数据通道。
         */
        private ToolDataChannel toolDataChannel;
        /**
         * 技能目录。
         */
        private Path skillsDir;
        /**
         * Versatile 透传缓冲。
         */
        private VersatilePassthroughBuffer passthroughBuffer;
        /**
         * DeepAgent 引用（供 Rail 访问 workspace）。
         */
        private DeepAgent deepAgent;
        /**
         * Todo 数据层（catalog entries + dynamic paths）。
         */
        private EdpaTodolist edpaTodolist;
        /**
         * 话术配置（注入 EdpaEventRail A 面 + ScriptsRail B 面）。
         */
        private SysScriptsConfig scripts;
        /**
         * Redis Todo 存储（UC-03~UC-11 主路径；null 时 Rail 内部回落文件/缓存）。
         */
        private RedisTodoStore redisTodoStore;
        /**
         * Agent 名称（从 openjiuwen.service.a2a.agent-name 配置读取）。
         */
        private String agentName;
        /**
         * SysOperation 双模式门面（可为 null，sandbox.enabled=false 时使用原有 ProcessBuilder）。
         */
        private SysOperation sysOp;
        /**
         * 沙箱网关配置。
         */
        private SandboxGatewayConfig gatewayConfig;
        /**
         * 治理装饰 SandboxClient（需求2路径，可为 null）。
         */
        private SandboxClient decoratedSandboxClient;

        public EdpConfig getEdpConfig() {
            return edpConfig;
        }

        public void setEdpConfig(EdpConfig edpConfig) {
            this.edpConfig = edpConfig;
        }

        public EdpaSpringBootConfig getSpringBootConfig() {
            return springBootConfig;
        }

        public void setSpringBootConfig(EdpaSpringBootConfig springBootConfig) {
            this.springBootConfig = springBootConfig;
        }

        public ActRuleConfig getActrule() {
            return actrule;
        }

        public void setActrule(ActRuleConfig actrule) {
            this.actrule = actrule;
        }

        public ToolDataChannel getToolDataChannel() {
            return toolDataChannel;
        }

        public void setToolDataChannel(ToolDataChannel toolDataChannel) {
            this.toolDataChannel = toolDataChannel;
        }

        public Path getSkillsDir() {
            return skillsDir;
        }

        public void setSkillsDir(Path skillsDir) {
            this.skillsDir = skillsDir;
        }

        public VersatilePassthroughBuffer getPassthroughBuffer() {
            return passthroughBuffer;
        }

        public void setPassthroughBuffer(VersatilePassthroughBuffer passthroughBuffer) {
            this.passthroughBuffer = passthroughBuffer;
        }

        public DeepAgent getDeepAgent() {
            return deepAgent;
        }

        public void setDeepAgent(DeepAgent deepAgent) {
            this.deepAgent = deepAgent;
        }

        public EdpaTodolist getEdpaTodolist() {
            return edpaTodolist;
        }

        public void setEdpaTodolist(EdpaTodolist edpaTodolist) {
            this.edpaTodolist = edpaTodolist;
        }

        public SysScriptsConfig getScripts() {
            return scripts;
        }

        public void setScripts(SysScriptsConfig scripts) {
            this.scripts = scripts;
        }

        public RedisTodoStore getRedisTodoStore() {
            return redisTodoStore;
        }

        public void setRedisTodoStore(RedisTodoStore redisTodoStore) {
            this.redisTodoStore = redisTodoStore;
        }

        public String getAgentName() {
            return agentName;
        }

        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }

        public SysOperation getSysOp() {
            return sysOp;
        }

        public void setSysOp(SysOperation sysOp) {
            this.sysOp = sysOp;
        }

        public SandboxGatewayConfig getGatewayConfig() {
            return gatewayConfig;
        }

        public void setGatewayConfig(SandboxGatewayConfig gatewayConfig) {
            this.gatewayConfig = gatewayConfig;
        }

        public SandboxClient getDecoratedSandboxClient() {
            return decoratedSandboxClient;
        }

        public void setDecoratedSandboxClient(SandboxClient decoratedSandboxClient) {
            this.decoratedSandboxClient = decoratedSandboxClient;
        }
    }
}
