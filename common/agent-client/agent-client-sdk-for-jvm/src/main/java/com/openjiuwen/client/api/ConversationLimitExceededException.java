/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

/**
 * 单个 {@link AgentClient} 生命周期内准入的不同 conversationId 超过配置上限。
 *
 * <p>该异常由 {@link AgentClient#invoke(InvocationRequest)} 在创建任何 Transport 请求前同步抛出，
 * 因而超限调用不会产生服务端 invocation 或 Task。它是客户端容量配置错误，不可通过原样重试恢复。
 *
 * @since 2026-08-27
 */
public final class ConversationLimitExceededException extends IllegalStateException implements ClassifiedError {
    private static final long serialVersionUID = 1L;

    private final String conversationId;
    private final int limit;

    /**
     * 构造异常。
     *
     * @param conversationId 被拒绝的新会话标识
     * @param limit 当前 Client 的不同会话上限
     */
    public ConversationLimitExceededException(String conversationId, int limit) {
        super("AgentClient distinct conversation limit exceeded: limit=" + limit
                + ", rejectedConversationId=" + conversationId);
        this.conversationId = conversationId;
        this.limit = limit;
    }

    /** @return 被拒绝的新会话标识 */
    public String conversationId() {
        return conversationId;
    }

    /** @return 当前 Client 的不同会话上限 */
    public int limit() {
        return limit;
    }

    @Override
    public String code() {
        return ErrorCodes.CONVERSATION_LIMIT_EXCEEDED;
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
