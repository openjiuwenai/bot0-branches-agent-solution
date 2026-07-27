/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一次智能体调用的请求（FEAT-006 §L-02/03/04/05，L2 Feat-Func-006 §2.3）。
 *
 * <p>标识符所有权：
 * <ul>
 *   <li>{@code conversationId} —— 业务应用拥有，表达上下文生命周期（映射到 A2A {@code message.contextId}）。</li>
 *   <li>{@code invocationId} —— 客户端拥有的调用事务标识（映射到 A2A {@code message.messageId}），
 *       同时用作幂等键的默认值。</li>
 * </ul>
 * runtime 侧的 {@code taskId} 不由业务传入，由 SDK 在收到受理事件后内部映射。
 * @since 2026-07-27
 */
public final class InvocationRequest {

    private final String agentId;
    private final String conversationId;
    private final InvocationMode mode;
    private final String input;
    private final String invocationId;
    private final String idempotencyKey;
    private final Duration deadline;
    private final ToolExposurePolicy exposure;
    private final Map<String, String> attributes;
    private final String credentialToken;

    private InvocationRequest(Builder b) {
        // agentId 可选（Feat-Func-011 §4.9 AC-4）：缺省时由网关路由到默认 Agent；空白串归一化为 null，避免上 wire 空值。
        this.agentId = (b.agentId != null && !b.agentId.isBlank()) ? b.agentId : null;
        this.conversationId = Objects.requireNonNull(b.conversationId, "conversationId");
        this.mode = Objects.requireNonNull(b.mode, "mode");
        this.input = Objects.requireNonNull(b.input, "input");
        this.invocationId = (b.invocationId != null) ? b.invocationId : "inv-" + UUID.randomUUID();
        this.idempotencyKey = (b.idempotencyKey != null) ? b.idempotencyKey : this.invocationId;
        this.deadline = b.deadline;
        this.exposure = b.exposure;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
        this.credentialToken = b.credentialToken;
    }

    /**
     * 目标 Agent 标识；为空表示不指定，交由网关按默认 Agent 路由（Feat-Func-011 §4.9 AC-4）。
     *
     * @return 目标 Agent 标识；为空表示不指定，交由网关按默认 Agent 路由（Feat-Func-011 §4.9 AC-4）。
     */    public Optional<String> agentId() {
        return Optional.ofNullable(agentId);
    }

    public String conversationId() {
        return conversationId;
    }

    public InvocationMode mode() {
        return mode;
    }

    public String input() {
        return input;
    }

    /**
     * 客户端调用事务标识，同时是公开的调用句柄 {@code invocationRef}。
     *
     * @return 客户端调用事务标识，同时是公开的调用句柄 {@code invocationRef}。
     */    public String invocationId() {
        return invocationId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Optional<Duration> deadline() {
        return Optional.ofNullable(deadline);
    }

    /**
     * 本次调用级别的工具暴露策略；为空表示不额外暴露（沿用会话级默认）。
     *
     * @return 本次调用级别的工具暴露策略；为空表示不额外暴露（沿用会话级默认）。
     */    public Optional<ToolExposurePolicy> exposure() {
        return Optional.ofNullable(exposure);
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    /**
     * 透传给网关的凭证（如 Bearer token）；SDK 不解释其内容。
     *
     * @return 透传给网关的凭证（如 Bearer token）；SDK 不解释其内容。
     */    public Optional<String> credentialToken() {
        return Optional.ofNullable(credentialToken);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String agentId;
        private String conversationId;
        private InvocationMode mode = InvocationMode.STREAMING;
        private String input;
        private String invocationId;
        private String idempotencyKey;
        private Duration deadline;
        private ToolExposurePolicy exposure;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String credentialToken;

        public Builder agentId(String v) {
            this.agentId = v;
            return this;
        }

        public Builder conversationId(String v) {
            this.conversationId = v;
            return this;
        }

        public Builder mode(InvocationMode v) {
            this.mode = v;
            return this;
        }

        public Builder input(String v) {
            this.input = v;
            return this;
        }

        public Builder invocationId(String v) {
            this.invocationId = v;
            return this;
        }

        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        public Builder deadline(Duration v) {
            this.deadline = v;
            return this;
        }

        public Builder exposure(ToolExposurePolicy v) {
            this.exposure = v;
            return this;
        }

        public Builder attribute(String k, String v) {
            this.attributes.put(k, v);
            return this;
        }

        public Builder credentialToken(String v) {
            this.credentialToken = v;
            return this;
        }

        public InvocationRequest build() {
            return new InvocationRequest(this);
        }
    }
}
