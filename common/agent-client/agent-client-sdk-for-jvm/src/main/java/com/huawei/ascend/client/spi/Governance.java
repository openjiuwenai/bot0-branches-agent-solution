/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.spi;

import com.huawei.ascend.client.tool.spi.LocalToolDescriptor;
import com.huawei.ascend.client.tool.spi.ToolExecutionContext;
import com.huawei.ascend.client.tool.spi.ToolInvocation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 本地治理 SPI（FEAT-007 §L-07）。在本地工具执行前施加"策略校验"与"人工/自动审批"。
 *
 * <p>SDK 编排顺序：PolicyGuard（同步硬门禁）→ 若 {@code requiresApproval} 则 ApprovalProvider（可异步）
 * → 通过后才真正执行工具。任一环节拒绝都会产出 {@code REJECTED} 结果续传给服务端，且工具不被执行。
 *
 * @since 2026-07-27
 */
public interface Governance {
    /**
     * 策略门禁：对将要执行的工具调用做同步准入判定。
     */
    interface PolicyGuard {
        /**
         * 准入决策。
         *
         * @param descriptor LocalToolDescriptor
         * @param invocation ToolInvocation
         * @param context ToolExecutionContext
         * @return 准入决策
         */
        Decision check(LocalToolDescriptor descriptor, ToolInvocation invocation, ToolExecutionContext context);

        /**
         * 放行一切的门禁。
         *
         * @return 放行一切的门禁
         */
        static PolicyGuard allowAll() {
            return (d, i, c) -> Decision.allow();
        }
    }

    /**
     * 审批提供者：对需要审批的工具调用给出批准/拒绝。
     */
    interface ApprovalProvider {
        /**
         * 审批决策 future。
         *
         * @param descriptor LocalToolDescriptor
         * @param invocation ToolInvocation
         * @param context ToolExecutionContext
         * @return 审批决策 future
         */
        CompletionStage<ApprovalDecision> requestApproval(
                LocalToolDescriptor descriptor, ToolInvocation invocation, ToolExecutionContext context);

        /**
         * 自动批准的提供者。
         *
         * @return 自动批准的提供者
         */
        static ApprovalProvider autoApprove() {
            return (d, i, c) -> CompletableFuture.completedFuture(ApprovalDecision.approve());
        }

        /**
         * 自动拒绝的提供者。
         *
         * @param reason String
         * @return 自动拒绝的提供者
         */
        static ApprovalProvider autoDeny(String reason) {
            return (d, i, c) -> CompletableFuture.completedFuture(ApprovalDecision.denied(reason));
        }
    }

    record Decision(boolean allowed, String errorCode, String reason) {
        /**
         * 放行决策。
         *
         * @return 放行决策
         */
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        /**
         * 拒绝决策。
         *
         * @param errorCode 错误码
         * @param reason 拒绝原因
         * @return 拒绝决策
         */
        public static Decision deny(String errorCode, String reason) {
            return new Decision(false, errorCode, reason);
        }
    }

    record ApprovalDecision(boolean approved, String reason) {
        /**
         * 批准决策。
         *
         * @return 批准决策
         */
        public static ApprovalDecision approve() {
            return new ApprovalDecision(true, null);
        }

        /**
         * 拒绝决策。
         *
         * @param reason 拒绝原因
         * @return 拒绝决策
         */
        public static ApprovalDecision denied(String reason) {
            return new ApprovalDecision(false, reason);
        }
    }
}
