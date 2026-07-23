/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent;

import com.huawei.ascend.edp.handler.EdpaExtHandler;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;
import com.openjiuwen.service.spec.spi.AgentHandler;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Layer 2: 深度定制示例
 *
 * <p>演示如何在 Layer 1（配置驱动）基础上，通过 Java 代码注册自定义工具和 Rail。
 * 以 wealth-demo 场景（理财购买）作为基础场景。
 *
 * <p>核心模式：
 * <ol>
 *   <li>注入 {@link AgentHandler} Bean（实际类型为 {@link EdpaExtHandler}）</li>
 *   <li>强转为 {@link EdpaExtHandler}，通过 {@code getDeepAgent()} 获取 {@link DeepAgent}</li>
 *   <li>通过 {@code deepAgent.registerHarnessTool(tool)} 注册自定义工具</li>
 *   <li>通过 {@code deepAgent.getAgent().registerRail(rail)} 注册自定义 Rail</li>
 * </ol>
 *
 * <p>执行时机：Spring 容器初始化完成后（{@code @PostConstruct}）自动执行，
 * 此时 EDPA 引擎已完成初始化，DeepAgent 已就绪。
 *
 * <p>注意：本类仅作为示例，客户可根据实际需求修改或删除。
 *
 * @since 2026-01-01
 */

@Configuration
public class CustomerAgentConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomerAgentConfig.class);

    /** AgentHandler 由 EDPA 引擎自动注入，实际类型为 EdpaExtHandler */
    private final AgentHandler agentHandler;

    /**
     * 构造函数注入：Spring 自动注入 {@link AgentHandler} Bean。
     *
     * @param agentHandler EDPA 引擎注册的 AgentHandler
     */

    public CustomerAgentConfig(AgentHandler agentHandler) {
        this.agentHandler = agentHandler;
    }

    /**
     * 注册自定义扩展：在 Spring 容器初始化完成后执行。
     *
     * <p>执行流程：
     * 1. 校验 agentHandler 是否为 EdpaExtHandler（确保 EDPA 引擎已加载）
     * 2. 获取 DeepAgent 实例（确保 Agent 已初始化）
     * 3. 注册自定义工具（greeting）
     * 4. 注册自定义 Rail（CustomerAuditRail）
     */

    @PostConstruct
    public void registerCustomExtensions() {
        // 校验：确保 AgentHandler 是 EDPA 引擎的 EdpaExtHandler
        if (!(agentHandler instanceof EdpaExtHandler edpaHandler)) {
            LOGGER.warn("AgentHandler is not EdpaExtHandler, skip custom registration");
            return;
        }

        // 获取 DeepAgent 实例（EDPA 引擎初始化后可用）
        DeepAgent deepAgent = edpaHandler.getDeepAgent();
        if (deepAgent == null) {
            LOGGER.warn("DeepAgent is null, skip custom registration");
            return;
        }

        // --- 注册自定义工具 ---
        // LocalFunction：本地函数工具，LLM 可直接调用
        // 注册后 LLM 可通过 function calling 调用 greeting 工具
        LocalFunction greetingTool = buildGreetingTool();
        deepAgent.registerHarnessTool(greetingTool);
        LOGGER.info("[LAYER2] Custom tool registered: {}", greetingTool.getCard().getId());

        // --- 注册自定义 Rail ---
        // DeepAgentRail：深度代理 Rail，在模型调用前后注入自定义逻辑
        // priority=15：低于内置 LogRail(10)，在日志之后执行
        CustomerAuditRail auditRail = new CustomerAuditRail();
        deepAgent.getAgent().registerRail(auditRail);
        LOGGER.info("[LAYER2] Custom rail registered: CustomerAuditRail (priority={})", auditRail.priority());
    }

    /**
     * 构建自定义问候工具。
     *
     * <p>该工具接收 "name" 参数，返回个性化问候语。
     * LLM 可通过 function calling 调用此工具。
     *
     * <p>工具 schema 示例：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "name": { "type": "string", "description": "The name of the person to greet" }
     *   },
     *   "required": ["name"]
     * }
     * </pre>
     *
     * @return 配置好的 LocalFunction 实例
     */

    private LocalFunction buildGreetingTool() {
        // 构建工具参数 schema（JSON Schema 格式）
        Map<String, Object> paramsSchema = new HashMap<>();
        paramsSchema.put("type", "object");

        // 定义参数列表
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> nameParam = new HashMap<>();
        nameParam.put("type", "string");
        nameParam.put("description", "The name of the person to greet");
        properties.put("name", nameParam);

        paramsSchema.put("properties", properties);
        paramsSchema.put("required", java.util.List.of("name"));

        // 构建 ToolCard：工具的元数据（ID、名称、描述、参数 schema）
        ToolCard card = ToolCard.builder()
                .id("customer_greeting")
                .name("greeting")
                .description("Generate a personalized greeting message for the given name")
                .inputParams(paramsSchema)
                .build();

        // 创建 LocalFunction：ToolCard + 执行逻辑（Lambda）
        // LLM 调用 greeting 工具时，执行此 Lambda 并返回结果
        return new LocalFunction(card, inputs -> {
            Object nameObj = inputs.get("name");
            String name = nameObj != null ? nameObj.toString() : "Guest";
            String message = "Hello, " + name + "! Welcome to our service.";
            LOGGER.info("[LAYER2] greeting tool called: name={}", name);
            return Map.of("greeting", message);
        });
    }

    /**
     * 自定义审计 Rail：在模型调用前后记录日志。
     *
     * <p>继承 {@link DeepAgentRail}，覆写以下回调：
     * <ul>
     *   <li>{@link #beforeModelCall}：模型调用前执行（如记录请求日志）</li>
     *   <li>{@link #afterModelCall}：模型调用后执行（如记录响应日志、耗时统计）</li>
     * </ul>
     *
     * <p>优先级 priority=15：低于内置 LogRail（priority=10），
     * 确保在内置日志之后执行，不干扰核心流程。
     *
     * <p>Rail 执行顺序（priority 升序）：
     * <pre>
     *   LogRail(10) → CustomerAuditRail(15) → ... → 模型调用 → ...(倒序)
     * </pre>
     */

    public static class CustomerAuditRail extends DeepAgentRail {
        /**
         * Rail 优先级：数字越小越先执行。
         * 15 = 在内置 LogRail(10) 之后执行。
         *
         * @return 优先级值
         */

        @Override
        public int priority() {
            return 15;
        }

        /**
         * 模型调用前回调：记录会话 ID，可用于审计或监控。
         *
         * @param ctx 回调上下文，包含 Session、消息历史等信息
         */

        @Override
        public void beforeModelCall(AgentCallbackContext ctx) {
            String sessionId = ctx.getSession() != null ? ctx.getSession().getSessionId() : "unknown";
            LOGGER.info("[CUSTOMER-AUDIT] beforeModelCall: session={}", sessionId);
        }

        /**
         * 模型调用后回调：记录会话 ID，可用于响应审计或耗时统计。
         *
         * @param ctx 回调上下文，包含 Session、模型响应结果等信息
         */

        @Override
        public void afterModelCall(AgentCallbackContext ctx) {
            String sessionId = ctx.getSession() != null ? ctx.getSession().getSessionId() : "unknown";
            LOGGER.info("[CUSTOMER-AUDIT] afterModelCall: session={}", sessionId);
        }
    }
}
