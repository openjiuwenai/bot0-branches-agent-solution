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
     * 订阅调用树更新。
     *
     * @param invocationRef 调用引用
     * @return 调用树更新流
     */
    Flow.Publisher<CallTreeSnapshot> callTree(String invocationRef);

    /**
     * 获取当前调用树快照。
     *
     * @param invocationRef 调用引用
     * @return 当前调用树快照
     */

    Optional<CallTreeSnapshot> currentCallTree(String invocationRef);
}
