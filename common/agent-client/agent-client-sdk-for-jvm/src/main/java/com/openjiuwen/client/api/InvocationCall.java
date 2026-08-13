/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import com.openjiuwen.client.api.calltree.CallTreeSnapshot;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * 一次进行中调用的句柄（FEAT-006 §2.4）。由 {@link AgentClient#invoke} 立即返回，
 * 不阻塞在网络上。业务通过它订阅事件流、等待终态，或用 {@link #invocationRef()} 发起后续操作。
 *
 * <p>受理回执通过 {@link #accepted()} 结算为 {@link Handle}（携带诊断用 {@code diagnosticTaskRef}），
 * 也可经 {@link #events()} 订阅 {@link InvocationEvent.Accepted} 事件获取同一信息。
 *
 * @since 2026-07-27
 */
public interface InvocationCall extends AutoCloseable {
    /**
     * 客户端拥有的调用句柄，用于 continueInput 等后续操作。
     *
     * @return 调用句柄
     */
    String invocationRef();

    /**
     * 当前调用所属的会话标识。
     *
     * @return 会话标识
     */
    String conversationId();

    /**
     * 调用被服务端受理后结算的回执。{@link Handle#diagnosticTaskRef()} 仅用于诊断/日志，非业务主键。
     * 与事件流中的 {@link InvocationEvent.Accepted} 携带同一信息，业务可任选其一消费。
     *
     * @return 受理回执 future
     */
    CompletionStage<Handle> accepted();

    /**
     * 标准化事件流。对于 client_tool 类型的 {@code INPUT_REQUIRED}，SDK 会自动就地执行并续传，
     * 业务侧订阅到的仍是连续的状态/内容事件，直至终态。
     *
     * @return 事件流发布者
     */
    Flow.Publisher<InvocationEvent> events();

    /**
     * 订阅调用树最新值。旧 Transport 或不支持过程树的调用返回一个立即完成的 Publisher；
     * SDK 内置 Transport 会在订阅后立即发布当前快照。
     *
     * @return 调用树快照流
     */
    default Flow.Publisher<CallTreeSnapshot> callTree() {
        return superCallTreePublisher();
    }

    /** SDK 内部与兼容实现共用的空 Publisher。 */
    static Flow.Publisher<CallTreeSnapshot> superCallTreePublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long n) {
                if (!done) {
                    done = true;
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                done = true;
            }
        });
    }

    /**
     * 在调用到达终态时完成，携带最终快照。
     *
     * @return 终态快照 future
     */
    CompletionStage<InvocationSnapshot> completion();

    /**
     * 关闭本调用句柄，释放本地订阅资源（不影响服务端 Task 状态）。
     */
    @Override
    void close();
}
