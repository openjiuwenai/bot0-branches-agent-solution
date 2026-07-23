package com.huawei.ascend.client.api;

/**
 * 调用受理回执（FEAT-006 §2.4）。
 *
 * <p>由 {@link InvocationCall#accepted} 在调用被服务端受理后结算。携带业务侧句柄
 * {@code invocationRef}、所属会话 {@code conversationId}，以及仅用于诊断/日志的
 * {@code diagnosticTaskRef}（对应服务端 {@code taskId}，不作操作句柄）。
 *
 * <p>业务用 {@code invocationRef} 发起后续操作（如 {@link AgentClient#continueInput}），
 * 不应直接使用 {@code diagnosticTaskRef} 操作 Task。
 */
public record Handle(String invocationRef, String conversationId, String diagnosticTaskRef) {
}
