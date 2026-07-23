package com.huawei.ascend.client.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.client.internal.DefaultAgentClient;
import com.huawei.ascend.client.internal.DefaultToolRegistry;
import com.huawei.ascend.client.internal.InMemoryStateStore;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.state.spi.ClientStateStore;
import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.transport.spi.CredentialProvider;
import com.huawei.ascend.client.transport.spi.TransportProvider;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 构造 {@link AgentClient} 的入口工厂。业务通过它注入具体传输实现与治理/状态存储策略。
 *
 * <p>唯一必需项是 {@link Builder#transport}（决定 wire 协议与网关地址）。其余均有可用于验证/开发的默认值。
 */
public final class AgentClients {

    private AgentClients() {
    }

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

        public Builder transport(TransportProvider v) {
            this.transport = v;
            return this;
        }

        /**
         * 凭证提供者：为到网关的每一次 HTTP 请求附带 Bearer 令牌（Feat-Func-011 强制鉴权）。
         * 连接真实网关时必需；本地假网关可省略。
         */
        public Builder credentialProvider(CredentialProvider v) {
            this.credentialProvider = v;
            return this;
        }

        public Builder toolRegistry(LocalToolRegistry v) {
            this.registry = v;
            return this;
        }

        public Builder stateStore(ClientStateStore v) {
            this.stateStore = v;
            return this;
        }

        public Builder policyGuard(Governance.PolicyGuard v) {
            this.policyGuard = v;
            return this;
        }

        public Builder approvalProvider(Governance.ApprovalProvider v) {
            this.approvalProvider = v;
            return this;
        }

        public Builder toolExecutor(ExecutorService v) {
            this.toolExecutor = v;
            return this;
        }

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

        private static ExecutorService defaultExecutor() {
            ThreadFactory tf = new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "agent-client-tool-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            };
            return Executors.newFixedThreadPool(4, tf);
        }
    }
}
