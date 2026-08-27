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
import com.openjiuwen.client.transport.spi.RawFrameConsumer;
import com.openjiuwen.client.transport.spi.RawResponseObserver;
import com.openjiuwen.client.transport.spi.TransportProvider;
import com.openjiuwen.client.transport.a2a.GatewayTransportProvider;
import com.openjiuwen.client.transport.a2a.RuntimeTransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.time.Duration;
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
    /** 单个 Client 生命周期内准入的不同 conversationId 默认上限。 */
    public static final int DEFAULT_MAX_DISTINCT_CONVERSATIONS = 5;

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
        private RawFrameConsumer rawFrameConsumer;
        private RawResponseObserver rawResponseObserver;
        private Executor rawResponseExecutor;
        private int rawResponseQueueCapacity = 65536;
        private Duration rawResponseFlushTimeout = Duration.ofSeconds(5);
        private EndpointType endpointType = EndpointType.GATEWAY;
        private String endpointUrl;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private int maxDistinctConversations = DEFAULT_MAX_DISTINCT_CONVERSATIONS;

        /**
         * 设置外部传输提供者（与 endpointUrl 二选一）。目标所有权契约为默认不转移所有权，
         * 只有显式声明时才由 AgentClient 关闭。
         *
         * <p>当前默认实现尚未区分资源来源，{@link AgentClient#close()} 仍会关闭该实例。
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
         * 设置单个 AgentClient 生命周期内可准入的不同 conversationId 累计上限。
         *
         * <p>默认值为 {@value AgentClients#DEFAULT_MAX_DISTINCT_CONVERSATIONS}。同一 conversationId
         * 的多次 invoke 只占一个名额；continueInput 复用原会话，不新增名额。名额在 Client 生命周期内
         * 不回收，因此该配置同时约束累计会话数和任一时刻的活跃会话数。超限调用在发网前同步失败。
         *
         * @param v 正整数上限
         * @return Builder
         */
        public Builder maxDistinctConversations(int v) {
            if (v < 1) {
                throw new IllegalArgumentException("maxDistinctConversations must be positive");
            }
            this.maxDistinctConversations = v;
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
         * 设置原始 wire 帧旁路消费器。
         *
         * <p>仅在使用内置 A2A Transport（通过 {@link #endpointUrl} 构建）时生效；自定义
         * {@link #transport} 的实现若需要该能力，应自行注入并消费。
         *
         * @param v 原始帧消费器
         * @return 本构造器
         */
        public Builder rawFrameConsumer(RawFrameConsumer v) {
            this.rawFrameConsumer = v;
            return this;
        }

        /**
         * Sets the asynchronous structured wire-response observer.
         *
         * @param v structured observer
         * @return this builder
         */
        public Builder rawResponseObserver(RawResponseObserver v) {
            this.rawResponseObserver = v;
            return this;
        }

        /**
         * Sets the executor used for observation callbacks.
         *
         * @param v callback executor
         * @return this builder
         */
        public Builder rawResponseExecutor(Executor v) {
            this.rawResponseExecutor = v;
            return this;
        }

        /**
         * Sets the bounded best-effort observation queue capacity.
         * When full, the newest observation is dropped immediately; the HTTP/SSE path is never blocked.
         *
         * @param v queue capacity
         * @return this builder
         */
        public Builder rawResponseQueueCapacity(int v) {
            if (v < 1) {
                throw new IllegalArgumentException("raw response queue capacity must be positive");
            }
            this.rawResponseQueueCapacity = v;
            return this;
        }

        /**
         * Sets the maximum time close() waits for queued observations to drain.
         *
         * @param v flush timeout
         * @return this builder
         */
        public Builder rawResponseFlushTimeout(Duration v) {
            this.rawResponseFlushTimeout = Objects.requireNonNull(v, "rawResponseFlushTimeout");
            if (v.isNegative()) {
                throw new IllegalArgumentException("rawResponseFlushTimeout must not be negative");
            }
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
         * 设置外部工具执行线程池。
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
            if (transport != null && (rawFrameConsumer != null || rawResponseObserver != null
                    || rawResponseExecutor != null)) {
                throw new IllegalArgumentException(
                        "raw response observation must be configured on the custom TransportProvider");
            }
            TransportProvider resolvedTransport = transport;
            if (resolvedTransport == null) {
                if (endpointUrl == null || endpointUrl.isBlank()) {
                    throw new NullPointerException("transport or endpointUrl must be provided");
                }
                if (rawFrameConsumer != null && rawResponseObserver != null) {
                    throw new IllegalArgumentException(
                            "rawFrameConsumer and rawResponseObserver are mutually exclusive");
                }
                RawResponseObserver observer = rawResponseObserver;
                if (rawFrameConsumer != null) {
                    observer = event -> rawFrameConsumer.accept(
                            event.invocationRef(), event.conversationId(), event.body(), event.source().name());
                }
                if (observer != null && rawResponseExecutor == null && rawFrameConsumer == null) {
                    throw new IllegalArgumentException(
                            "rawResponseExecutor is required when observation is enabled");
                }
                Executor observerExecutor = rawResponseExecutor != null ? rawResponseExecutor
                        : java.util.concurrent.ForkJoinPool.commonPool();
                resolvedTransport = endpointType == EndpointType.RUNTIME
                        ? new RuntimeTransportProvider(endpointUrl, retryPolicy, observer, observerExecutor,
                                rawResponseQueueCapacity, rawResponseFlushTimeout)
                        : new GatewayTransportProvider(endpointUrl, retryPolicy, observer, observerExecutor,
                                rawResponseQueueCapacity, rawResponseFlushTimeout);
            }
            LocalToolRegistry reg = (registry != null) ? registry : new DefaultToolRegistry();
            ClientStateStore store = (stateStore != null) ? stateStore : new InMemoryStateStore();
            Governance.PolicyGuard guard =
                    (policyGuard != null) ? policyGuard : Governance.PolicyGuard.allowAll();
            Governance.ApprovalProvider approval =
                    (approvalProvider != null) ? approvalProvider : Governance.ApprovalProvider.autoApprove();
            ExecutorService exec = (toolExecutor != null) ? toolExecutor : defaultExecutor();
            ObjectMapper mapper = new ObjectMapper();
            return new DefaultAgentClient(resolvedTransport, reg, store, guard, approval, exec, mapper,
                    credentialProvider, maxDistinctConversations);
        }

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
