/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户输入续传请求（FEAT-006 §L-11）。
 *
 * <p>当某次调用进入 {@code INPUT_REQUIRED} 且属于"需要用户补充输入"（区别于 client_tool 由 SDK 自动驱动），
 * 业务用本请求把用户输入续传给同一个服务端 Task。它在 wire 上是一次针对既有 {@code taskId} 的
 * 同步 {@code SendMessage}（携带用户输入 {@code TextPart}，Feat-Func-011 §5.9.3）。
 *
 * <p>{@code relatedInvocationRef} 指向那次处于 INPUT_REQUIRED 的调用句柄，SDK 借此解析出内部 {@code taskId}。
 *
 * @since 2026-07-27
 */
public final class ContinueInputRequest {
    private final String conversationId;
    private final String relatedInvocationRef;
    private final String input;
    private final InvocationMode mode;
    private final String invocationId;
    private final String idempotencyKey;

    private ContinueInputRequest(Builder b) {
        this.conversationId = Objects.requireNonNull(b.conversationId, "conversationId");
        this.relatedInvocationRef = Objects.requireNonNull(b.relatedInvocationRef, "relatedInvocationRef");
        this.input = Objects.requireNonNull(b.input, "input");
        this.mode = Objects.requireNonNull(b.mode, "mode");
        this.invocationId = (b.invocationId != null) ? b.invocationId : "inv-" + UUID.randomUUID();
        this.idempotencyKey = (b.idempotencyKey != null) ? b.idempotencyKey : this.invocationId;
    }

    /**
     * 会话标识。
     *
     * @return 会话标识
     */
    public String conversationId() {
        return conversationId;
    }

    /**
     * 关联的调用句柄（指向处于 INPUT_REQUIRED 的那次调用）。
     *
     * @return 关联调用句柄
     */
    public String relatedInvocationRef() {
        return relatedInvocationRef;
    }

    /**
     * 用户补充输入文本。
     *
     * @return 用户输入
     */
    public String input() {
        return input;
    }

    /**
     * 调用模式。
     *
     * @return 调用模式
     */
    public InvocationMode mode() {
        return mode;
    }

    /**
     * 本次续传调用的标识。
     *
     * @return 调用标识
     */
    public String invocationId() {
        return invocationId;
    }

    /**
     * 幂等键，用于服务端去重。
     *
     * @return 幂等键
     */
    public String idempotencyKey() {
        return idempotencyKey;
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
     * {@link ContinueInputRequest} 的构造器。
     *
     * @since 2026-07-27
     */
    public static final class Builder {
        private String conversationId;
        private String relatedInvocationRef;
        private String input;
        private InvocationMode mode = InvocationMode.STREAMING;
        private String invocationId;
        private String idempotencyKey;

        /**
         * 设置会话标识。
         *
         * @param v 会话标识
         * @return 本构造器
         */
        public Builder conversationId(String v) {
            this.conversationId = v;
            return this;
        }

        /**
         * 设置关联调用句柄。
         *
         * @param v 关联调用句柄
         * @return 本构造器
         */
        public Builder relatedInvocationRef(String v) {
            this.relatedInvocationRef = v;
            return this;
        }

        /**
         * 设置用户输入。
         *
         * @param v 用户输入
         * @return 本构造器
         */
        public Builder input(String v) {
            this.input = v;
            return this;
        }

        /**
         * 设置调用模式。
         *
         * @param v 调用模式
         * @return 本构造器
         */
        public Builder mode(InvocationMode v) {
            this.mode = v;
            return this;
        }

        /**
         * 设置调用标识。
         *
         * @param v 调用标识
         * @return 本构造器
         */
        public Builder invocationId(String v) {
            this.invocationId = v;
            return this;
        }

        /**
         * 设置幂等键。
         *
         * @param v 幂等键
         * @return 本构造器
         */
        public Builder idempotencyKey(String v) {
            this.idempotencyKey = v;
            return this;
        }

        /**
         * 构建请求实例。
         *
         * @return 请求实例
         */
        public ContinueInputRequest build() {
            return new ContinueInputRequest(this);
        }
    }
}
