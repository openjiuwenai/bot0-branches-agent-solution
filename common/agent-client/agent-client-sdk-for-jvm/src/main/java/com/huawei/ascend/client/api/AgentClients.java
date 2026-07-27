/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import com.huawei.ascend.client.internal.DefaultAgentClient;
import com.huawei.ascend.client.internal.DefaultToolRegistry;
import com.huawei.ascend.client.internal.InMemoryStateStore;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.state.spi.ClientStateStore;
import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.transport.spi.CredentialProvider;
import com.huawei.ascend.client.transport.spi.TransportProvider;

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
    }

    /**
     * 创建构造器。
     *
     * @return 构造器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private TransportProvider transport;
        private LocalToolRegistry registry;
        private ClientStateStore stateStore;
        private Governance.PolicyGuard policyGuard;
        private Governance.ApprovalProvider approvalProvider;
        private ExecutorService toolExecutor;
        private CredentialProvider credentialProvider;

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
            Objects.requireNonNull(transport, "transport must be provided");
            LocalToolRegistry reg = (registry != null) ? registry : new DefaultToolRegistry();
            ClientStateStore store = (stateStore != null) ? stateStore : new InMemoryStateStore();
            Governance.PolicyGuard guard =
                    (policyGuard != null) ? policyGuard : Governance.PolicyGuard.allowAll();
            Governance.ApprovalProvider approval =
                    (approvalProvider != null) ? approvalProvider : Governance.ApprovalProvider.autoApprove();
            ExecutorService exec = (toolExecutor != null) ? toolExecutor : defaultExecutor();
            ObjectMapper mapper = new ObjectMapper();
            return new DefaultAgentClient(
                    transport, reg, store, guard, approval, exec, mapper, credentialProvider);
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
                        // best-effort：工具线程未捕获异常不打断客户端主流程。
                    });
                    return t;
                }
            };
            return new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), tf);
        }
    }
}
