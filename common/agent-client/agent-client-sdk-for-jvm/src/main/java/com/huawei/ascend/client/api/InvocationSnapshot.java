package com.huawei.ascend.client.api;

/**
 * 某次调用在某一时刻的状态快照投影（FEAT-006 §L-07/12）。
 *
 * <p>由 {@link AgentClient#getInvocation} / {@link AgentClient#cancel} 等操作返回，
 * 反映"客户端此刻观测到的"状态。它<b>不是</b>服务端权威状态，可能滞后。
 * 可空字段（diagnosticTaskRef/pendingToolCall/outputText/errorCode/message）以 null 表示不适用。
 */
public record InvocationSnapshot(
        String invocationRef,
        TaskState state,
        boolean terminal,
        String diagnosticTaskRef,
        InvocationEvent.ToolCall pendingToolCall,
        String outputText,
        String errorCode,
        String message) {

    public static InvocationSnapshot unknown(String invocationRef) {
        return new InvocationSnapshot(invocationRef, TaskState.UNKNOWN, false, null, null, null, null, null);
    }
}
