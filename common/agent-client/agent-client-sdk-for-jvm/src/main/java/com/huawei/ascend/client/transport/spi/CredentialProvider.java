/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.spi;

/**
 * 凭证提供者 SPI（Feat-Func-011 §4.9 / §5.9 / §6.9）：为每一次到网关的 HTTP 请求提供 Bearer 令牌。
 *
 * <p>网关对每个 HTTP 请求都强制鉴权（缺失记 {@code AUTH_MISSING}、非法记 {@code AUTH_INVALID}，一律拒绝），
 * 因此 SDK 在"创建调用 / 用户输入续传 / 工具结果续传"的每一次请求上都会附带
 * {@code Authorization: Bearer <token>}。令牌来源与刷新由业务实现，SDK 不解释其内容。
 *
 * <p>{@code InvocationRequest.credentialToken()} 若显式给出则优先于本提供者（便于单次覆盖）。
 * 返回 {@code null} 表示本次不附带（通常仅用于本地假网关/无鉴权环境）。
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * 返回用于指定会话的凭证令牌。
     *
     * @param conversationId 业务会话标识，便于按会话/租户区分令牌；实现可忽略。
     * @return Bearer 令牌文本（可含或不含 {@code "Bearer "} 前缀，传输层会规范化），或 {@code null}。
     */
    String tokenFor(String conversationId);

    /** 恒定令牌的便捷实现。 */
    static CredentialProvider staticToken(String token) {
        return conversationId -> token;
    }
}
