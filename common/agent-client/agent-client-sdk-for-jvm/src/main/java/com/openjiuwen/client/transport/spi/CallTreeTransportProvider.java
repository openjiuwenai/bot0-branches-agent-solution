/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

import com.openjiuwen.client.api.calltree.CallTreeSnapshot;

import java.util.Optional;
import java.util.concurrent.Flow;

/**
 * 可提供调用树的 Transport 增强 SPI；不改变旧 TransportProvider 的二进制契约。
 *
 * @since 2026-07-27
 */
public interface CallTreeTransportProvider extends TransportProvider {
    /**
     * 返回指定调用的调用树事件流。
     *
     * @param invocationRef 调用句柄
     * @return 调用树事件流
     */
    Flow.Publisher<CallTreeSnapshot> callTree(String invocationRef);

    /**
     * 返回指定调用的当前调用树快照。
     *
     * @param invocationRef 调用句柄
     * @return 当前调用树快照，可能为空
     */
    Optional<CallTreeSnapshot> currentCallTree(String invocationRef);
}
