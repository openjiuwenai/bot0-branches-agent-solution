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

    public String conversationId() {
        return conversationId;
    }

    public String relatedInvocationRef() {
        return relatedInvocationRef;
    }

    public String input() {
        return input;
    }

    public InvocationMode mode() {
        return mode;
    }

    public String invocationId() {
        return invocationId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String conversationId;
        private String relatedInvocationRef;
        private String input;
        private InvocationMode mode = InvocationMode.STREAMING;
        private String invocationId;
        private String idempotencyKey;

        public Builder conversationId(String v) {
            this.conversationId = v;
            return this;
        }

        public Builder relatedInvocationRef(String v) {
            this.relatedInvocationRef = v;
            return this;
        }

        public Builder input(String v) {
            this.input = v;
            return this;
        }

        public Builder mode(InvocationMode v) {
            this.mode = v;
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

        public ContinueInputRequest build() {
            return new ContinueInputRequest(this);
        }
    }
}
