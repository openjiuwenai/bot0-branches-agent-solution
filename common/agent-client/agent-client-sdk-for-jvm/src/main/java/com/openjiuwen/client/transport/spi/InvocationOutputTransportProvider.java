/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

import java.util.Optional;

/** 可提供 rich Artifact 物化根输出的 Transport 增强 SPI。 */
public interface InvocationOutputTransportProvider extends TransportProvider {
    /**
     * 返回当前已物化的根业务输出。空 Optional 表示尚未观察到根业务 Artifact；
     * {@code Optional.of("")} 表示 Artifact 存在但其当前文本为空。
     *
     * @param invocationRef 调用引用
     * @return 权威根输出（若已观察到）
     */
    Optional<String> currentOutputText(String invocationRef);
}
