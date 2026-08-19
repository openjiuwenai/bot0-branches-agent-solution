/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import com.openjiuwen.client.internal.DefaultAgentClient;
import com.openjiuwen.client.internal.DefaultToolRegistry;
import com.openjiuwen.client.internal.InMemoryStateStore;
import com.openjiuwen.client.spi.Governance;
import com.openjiuwen.client.state.spi.ClientStateStore;
import com.openjiuwen.client.tool.spi.LocalToolRegistry;
import com.openjiuwen.client.transport.spi.CredentialProvider;
import com.openjiuwen.client.transport.spi.TransportProvider;
import com.openjiuwen.client.transport.a2a.GatewayTransportProvider;
import com.openjiuwen.client.transport.a2a.RuntimeTransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 构造 {@link AgentClient} 的入口工厂。业务通过它注入具体传输实现与治理/状态存储策略。
 *
 * <p>唯一必需项是 {@link Builder#transport}（决定 wire 协议与网关地址）。其余均有可用于验证/开发的默认值。
 *
 * @since 2026-07-27
 */
public final class AgentClients {
    private AgentClients() {
        throw new AssertionError("utility class, no instances");
    }

    /**
     * 创建构造器。
     *
     * @return 构造器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AgentClient} 的构造器：按需注入传输、工具注册表、治理、执行器等组件。
     *
     * @since 2026-07-27
     */
    public static final class Builder {
        private TransportProvider transport;
        private LocalToolRegistry registry;
        private ClientStateStore stateStore;
        private Governance.PolicyGuard policyGuard;
        private Governance.ApprovalProvider approvalProvider;
        private ExecutorService toolExecutor;
        private CredentialProvider credentialProvider;
        private EndpointType endpointType = EndpointType.GATEWAY;
        private String endpointUrl;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();

        /**
         * 设置传输提供者（必填，决定 wire 协议与网关地址）。
         *
         * @param v 传输提供者
         * @return 本构造器
         */
        public Builder transport(TransportProvider v) {
            this.transport = v;
            return this;
        }

        /**
         * 设置内置 A2A Transport 的 Endpoint 类型，默认 GATEWAY。
         *
         * @param v 端点类型
         * @return Builder
         */
        public Builder endpointType(EndpointType v) {
            this.endpointType = Objects.requireNonNull(v, "endpointType");
            return this;
        }

        /**
         * 设置内置 A2A Transport 的服务基址；SDK 自动补齐 /a2a。
         *
         * @param v 端点 URL
         * @return Builder
         */
        public Builder endpointUrl(String v) {
            this.endpointUrl = v;
            return this;
        }

        /**
         * 设置内置 A2A Transport 的链路异常恢复策略。
         *
         * <p>该配置控制 GetTask 周期性重试、SSE 重订阅、连续失败熔断和 Gateway
         * 幂等创建恢复。使用自定义 {@link #transport(TransportProvider)} 时应由自定义
         * Transport 自行应用恢复策略。
         *
         * @param v 重试策略
         * @return Builder
         */
        public Builder retryPolicy(RetryPolicy v) {
            this.retryPolicy = Objects.requireNonNull(v, "retryPolicy");
            return this;
        }

        /**
         * 凭证提供者：为到网关的每一次 HTTP 请求附带 Bearer 令牌（Feat-Func-011 强制鉴权）。
         * 连接真实网关时必需；本地假网关可省略。
         *
         * @param v 凭证提供者
         * @return 本构造器
         */
        public Builder credentialProvider(CredentialProvider v) {
            this.credentialProvider = v;
            return this;
        }

        /**
         * 设置本地工具注册表（默认空实现）。
         *
         * @param v 工具注册表
         * @return 本构造器
         */
        public Builder toolRegistry(LocalToolRegistry v) {
            this.registry = v;
            return this;
        }

        /**
         * 设置状态存储（默认内存实现）。
         *
         * @param v 状态存储
         * @return 本构造器
         */
        public Builder stateStore(ClientStateStore v) {
            this.stateStore = v;
            return this;
        }

        /**
         * 设置策略门禁（默认放行一切）。
         *
         * @param v 策略门禁
         * @return 本构造器
         */
        public Builder policyGuard(Governance.PolicyGuard v) {
            this.policyGuard = v;
            return this;
        }

        /**
         * 设置审批提供者（默认自动批准）。
         *
         * @param v 审批提供者
         * @return 本构造器
         */
        public Builder approvalProvider(Governance.ApprovalProvider v) {
            this.approvalProvider = v;
            return this;
        }

        /**
         * 设置工具执行线程池（默认 4 线程守护池）。
         *
         * @param v 工具执行线程池
         * @return 本构造器
         */
        public Builder toolExecutor(ExecutorService v) {
            this.toolExecutor = v;
            return this;
        }

        /**
         * 构建客户端实例。
         *
         * @return 客户端实例
         */
        public AgentClient build() {
            if (transport != null && endpointUrl != null) {
                throw new IllegalArgumentException("transport and endpointUrl are mutually exclusive");
            }
            TransportProvider resolvedTransport = transport;
            if (resolvedTransport == null) {
                if (endpointUrl == null || endpointUrl.isBlank()) {
                    throw new NullPointerException("transport or endpointUrl must be provided");
                }
                resolvedTransport = endpointType == EndpointType.RUNTIME
                        ? new RuntimeTransportProvider(endpointUrl, retryPolicy)
                        : new GatewayTransportProvider(endpointUrl, retryPolicy);
            }
            LocalToolRegistry reg = (registry != null) ? registry : new DefaultToolRegistry();
            ClientStateStore store = (stateStore != null) ? stateStore : new InMemoryStateStore();
            Governance.PolicyGuard guard =
                    (policyGuard != null) ? policyGuard : Governance.PolicyGuard.allowAll();
            Governance.ApprovalProvider approval =
                    (approvalProvider != null) ? approvalProvider : Governance.ApprovalProvider.autoApprove();
            ExecutorService exec = (toolExecutor != null) ? toolExecutor : defaultExecutor();
            ObjectMapper mapper = new ObjectMapper();
            return new DefaultAgentClient(
                    resolvedTransport, reg, store, guard, approval, exec, mapper, credentialProvider);
        }

        /**
         * defaultExecutor。
         *
         * @return defaultExecutor
         */

        private static ExecutorService defaultExecutor() {
            ThreadFactory tf = new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
                    t.setName("agent-client-tool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((thread, ex) -> {
                        // best-effort：工具线程未捕获异常不打断客户端主流程，仅记录日志。
                        java.util.logging.Logger.getLogger(AgentClients.class.getName())
                                .log(java.util.logging.Level.WARNING,
                                        "uncaught exception in tool executor thread " + thread.getName(), ex);
                    });
                    return t;
                }
            };
            return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), tf);
        }
    }
}
