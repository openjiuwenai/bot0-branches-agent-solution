/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import java.util.Set;

/**
 * 稳定错误码闭集与可重试判定（FEAT-006「错误分类」MUST，L2 Feat-Func-006 §5.3.1）。
 *
 * <p>治理错误必须按 <b>HTTP 状态 + 稳定 code</b> 分类，不得把所有非 2xx 折叠为同一种传输错误。
 * 其中两种幂等冲突 <b>HTTP 状态相同（409）但可重试性相反</b>，必须按 code 区分：
 * <ul>
 * <li>{@link #IDEMPOTENCY_PAYLOAD_MISMATCH} —— 同键绑定了不同正文，重试无意义，必须换键或报错。</li>
 * <li>{@link #IDEMPOTENCY_IN_FLIGHT} —— 同键同正文的前一次请求仍在途，退避后<b>用同键</b>重试即可。</li>
 * </ul>
 *
 * @since 2026-07-30
 */
public final class ErrorCodes {
    /**
     * 缺少凭据。不可重试。
     */
    public static final String AUTH_MISSING = "AUTH_MISSING";

    /**
     * 凭据无效。不可重试。
     */
    public static final String AUTH_INVALID = "AUTH_INVALID";

    /**
     * 越权。不可重试。
     */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /**
     * 请求参数非法（调用方缺陷）。不可重试。
     */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /**
     * 路由失败：网关找不到目标 Agent / 路由。不可重试。
     *
     * <p>与 {@link #TASK_NOT_FOUND} 区分：前者是「不知道往哪送」，后者是「Task 不存在」。
     */
    public static final String ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";

    /**
     * 服务端 Task 不存在（查询/续跑一个已回收或从未创建的 Task）。不可重试。
     */
    public static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";

    /**
     * 网关未开放该 A2A 方法。不可重试。
     *
     * <p>v0730 网关北向方法白名单只放行 {@code SendMessage} / {@code SendStreamingMessage} / {@code GetTask}；
     * {@code CancelTask} / {@code SubscribeToTask} 会返回该码（见评审文档 BLK-2/BLK-3）。
     *
     * <p>Gateway 冻结的原始 wire 错误码：请求方法未通过方法白名单校验。
     */
    public static final String GATEWAY_VALIDATION_METHOD = "VALIDATION_METHOD";

    /** 客户端语义别名；wire 值保持 Gateway 的 {@link #GATEWAY_VALIDATION_METHOD}。 */
    public static final String METHOD_NOT_SUPPORTED = GATEWAY_VALIDATION_METHOD;

    /**
     * 同幂等键绑定了不同正文。<b>不可重试</b>。
     */
    public static final String IDEMPOTENCY_PAYLOAD_MISMATCH = "IDEMPOTENCY_PAYLOAD_MISMATCH";

    /**
     * 同幂等键同正文的前一次请求仍在途。<b>可重试（须用同键）</b>。
     */
    public static final String IDEMPOTENCY_IN_FLIGHT = "IDEMPOTENCY_IN_FLIGHT";

    /**
     * 被限流。可重试。
     */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /**
     * 服务不可用。可重试。
     */
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    /**
     * 网络失败（连接/读超时/连接重置）。可重试。
     */
    public static final String NETWORK_ERROR = "NETWORK_ERROR";

    /**
     * Runtime 创建连接在取得 taskId 前失败；创建不会自动重发。
     */
    public static final String CREATE_FAILED_NO_TASK_ID = "CREATE_FAILED_NO_TASK_ID";

    /**
     * 已知 Task 的自动恢复连续失败达到熔断阈值。
     */
    public static final String RECOVERY_RETRY_EXHAUSTED = "RECOVERY_RETRY_EXHAUSTED";

    /**
     * client_tool interrupt 缺少 toolCallId/toolName，SDK 禁止猜测续传目标。
     */
    public static final String INPUT_RESUME_TARGET_MISSING = "INPUT_RESUME_TARGET_MISSING";

    /**
     * Client 自动观察已超过约定时限；服务端 Task 可能仍在运行。不可自动重试创建。
     */
    public static final String OBSERVATION_TIMEOUT = "OBSERVATION_TIMEOUT";

    /**
     * 服务端 Task 失败。不可重试（不是网络问题，不得包装为网络失败）。
     */
    public static final String AGENT_ERROR = "AGENT_ERROR";

    /**
     * 流在非终态下中断，进展不确定（FEAT-006 §5.1.4：中断不等于失败）。
     */
    public static final String STREAM_INTERRUPTED = "STREAM_INTERRUPTED";

    /**
     * Gateway 的可选断点游标已过期；Runtime 直连模式不使用该能力。
     */
    public static final String REPLAY_CURSOR_EXPIRED = "REPLAY_CURSOR_EXPIRED";

    /**
     * Task 已终态等原因导致 SubscribeToTask 不可建立；调用方应以 GetTask 收敛。
     */
    public static final String SUBSCRIPTION_UNAVAILABLE = "SUBSCRIPTION_UNAVAILABLE";

    /**
     * 未支持的调用模式。不可重试。
     */
    public static final String UNSUPPORTED_MODE = "UNSUPPORTED_MODE";

    /**
     * 声明了 {@code STREAMING} 但链路不提供流式承载。不可重试。
     *
     * <p>对齐 FEAT-006 §5.1.4「不支持流式的链路**不得静默降级**」：SDK 明确报错，
     * 而不是把非流式响应当成一条空流悄悄结束。
     */
    public static final String STREAMING_UNAVAILABLE = "STREAMING_UNAVAILABLE";

    /**
     * 关联的 invocation 不可续接（已终态 / 不处于等待输入 / 正等端侧工具结果 / 映射未建立）。不可重试。
     *
     * <p>对齐 FEAT-006 §5.1.3：此类续接必须返回<b>明确错误</b>，不得静默新建一个普通任务。
     */
    public static final String RELATED_NOT_RESUMABLE = "RELATED_NOT_RESUMABLE";

    private static final Set<String> RETRYABLE = Set.of(
            IDEMPOTENCY_IN_FLIGHT, RATE_LIMITED, SERVICE_UNAVAILABLE, NETWORK_ERROR);

    private ErrorCodes() {
        throw new AssertionError("utility class, no instances");
    }

    /**
     * 该错误码是否可以退避后重试。
     *
     * <p>未识别的错误码按<b>不可重试</b>处理：宁可让调用方看到明确失败，也不要陷入无效重试循环。
     *
     * @param code 错误码，允许为 null
     * @return 可重试返回 true
     */
    public static boolean isRetryable(String code) {
        return code != null && RETRYABLE.contains(code);
    }

    /**
     * 按 HTTP 状态推断稳定错误码，供网关未返回 {@code code} 字段时兜底。
     *
     * <p>注意：409 无法仅凭状态码区分两种幂等冲突，兜底取<b>不可重试</b>的
     * {@link #IDEMPOTENCY_PAYLOAD_MISMATCH}，避免误重试。网关正常返回 code 时不走本方法。
     *
     * @param httpStatus HTTP 状态码
     * @return 稳定错误码
     */
    public static String fromHttpStatus(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> VALIDATION_FAILED;
            case 401 -> AUTH_INVALID;
            case 403 -> PERMISSION_DENIED;
            case 404 -> ROUTE_NOT_FOUND;
            case 409 -> IDEMPOTENCY_PAYLOAD_MISMATCH;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> httpStatus / 100 == 5 ? SERVICE_UNAVAILABLE : "HTTP_" + httpStatus;
        };
    }
}
