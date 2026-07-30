/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

/**
 * 关联的 invocation 不可续接（FEAT-006 §5.1.3：必须返回明确错误，不得静默新建普通任务）。
 *
 * <p>由 {@link AgentClient#continueInput} 在本地预检失败时抛出，触发条件：
 * <ul>
 * <li>{@code relatedInvocationRef} 未知，或其 taskRef 映射尚未建立；</li>
 * <li>关联调用不处于 {@code INPUT_REQUIRED}（已终态或仍在执行）；</li>
 * <li>关联调用正等待端侧工具结果 —— 那条路由 SDK 自动续跑，业务不应插手（FRZ-1）。</li>
 * </ul>
 *
 * <p>继承 {@link IllegalStateException} 以兼容既有调用方的捕获写法，同时实现
 * {@link ClassifiedError}，使业务能拿到稳定错误码与可重试判定，而不必解析异常消息。
 *
 * @since 2026-07-30
 */
public final class InvocationNotResumableException extends IllegalStateException implements ClassifiedError {
    private static final long serialVersionUID = 1L;

    private final String relatedInvocationRef;

    /**
     * 构造异常。
     *
     * @param relatedInvocationRef 不可续接的关联调用句柄
     * @param message 说明为何不可续接
     */
    public InvocationNotResumableException(String relatedInvocationRef, String message) {
        super(message);
        this.relatedInvocationRef = relatedInvocationRef;
    }

    /**
     * 不可续接的关联调用句柄。
     *
     * @return 关联调用句柄
     */
    public String relatedInvocationRef() {
        return relatedInvocationRef;
    }

    @Override
    public String code() {
        return ErrorCodes.RELATED_NOT_RESUMABLE;
    }

    /**
     * 恒为 {@code false}：续接点不会因为重试而变得可续接，重试只会放大问题。
     *
     * @return 固定返回 false
     */
    @Override
    public boolean retryable() {
        return false;
    }
}
