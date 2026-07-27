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
 */
public interface Governance {

    /** 策略门禁：对将要执行的工具调用做同步准入判定。 */
    interface PolicyGuard {
        Decision check(LocalToolDescriptor descriptor, ToolInvocation invocation, ToolExecutionContext context);

        /** 放行一切（默认实现，仅用于验证/开发）。 */
        static PolicyGuard allowAll() {
            return (d, i, c) -> Decision.allow();
        }
    }

    /** 审批提供者：对需要审批的工具调用给出批准/拒绝。 */
    interface ApprovalProvider {
        CompletionStage<ApprovalDecision> requestApproval(
                LocalToolDescriptor descriptor, ToolInvocation invocation, ToolExecutionContext context);

        /** 自动批准（默认实现，仅用于验证/开发）。 */
        static ApprovalProvider autoApprove() {
            return (d, i, c) -> CompletableFuture.completedFuture(ApprovalDecision.approve());
        }

        /** 自动拒绝（安全默认，可用于强制业务显式接入审批）。 */
        static ApprovalProvider autoDeny(String reason) {
            return (d, i, c) -> CompletableFuture.completedFuture(ApprovalDecision.denied(reason));
        }
    }

    record Decision(boolean allowed, String errorCode, String reason) {
        public static Decision allow() {
            return new Decision(true, null, null);
        }

        public static Decision deny(String errorCode, String reason) {
            return new Decision(false, errorCode, reason);
        }
    }

    record ApprovalDecision(boolean approved, String reason) {
        public static ApprovalDecision approve() {
            return new ApprovalDecision(true, null);
        }

        public static ApprovalDecision denied(String reason) {
            return new ApprovalDecision(false, reason);
        }
    }
}
