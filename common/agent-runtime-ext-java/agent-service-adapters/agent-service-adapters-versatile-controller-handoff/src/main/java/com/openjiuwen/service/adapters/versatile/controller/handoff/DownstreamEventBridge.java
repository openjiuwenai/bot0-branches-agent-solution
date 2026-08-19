/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Bridges the RemoteAgentCaller EventObserver callbacks to the current
 * QueryStreamObserver as TYPE_CHUNK frames, reusing the runtime's canonical
 * part-to-text extraction (A2aPartContent). SDK event objects are never exposed
 * to the controller or northbound (spec 2.4/4.5).
 *
 * @since 2026-08-19
 */
public class DownstreamEventBridge implements RemoteAgentCaller.EventObserver {
    private final QueryStreamObserver current;
    private volatile CompletableFuture<?> futureToCancel;

    public DownstreamEventBridge(QueryStreamObserver current) {
        this.current = current;
    }

    /** executor 在发起 callOutcome 后绑定 future，实现协作式取消的"尽力终止下游调用"（spec 4.6）。 */
    public void bindFuture(CompletableFuture<?> future) {
        this.futureToCancel = future;
    }

    @Override
    public void onStatus(TaskStatusUpdateEvent event) {
        if (event.status() == null || event.status().message() == null) {
            return;
        }
        forward(A2aPartContent.extract(event.status().message().parts()));
    }

    @Override
    public void onArtifact(TaskArtifactUpdateEvent event) {
        if (event.artifact() == null) {
            return;
        }
        forward(A2aPartContent.extract(event.artifact().parts()));
    }

    private void forward(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        // not-in-scope 标记信封是协议信号而非业务内容：不透传给最终用户，
        // 由 executor 在 outcome.result 上检测后走重识别（spec 2.2/4.4）。
        if (HandoffSignals.isNotInScope(text)) {
            return;
        }
        if (current.isCancelled()) {
            CompletableFuture<?> future = futureToCancel;
            if (future != null) {
                future.cancel(true);
            }
            return;
        }
        current.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, text));
    }
}
