/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

/**
 * Agent Client 的服务端入口类型。
 *
 * @since 2026-08-13
 */
public enum EndpointType {
    /** 平台为中心，经 Agent Gateway 调用。 */
    GATEWAY,
    /** 业务为中心，直接调用单个 Agent Runtime。 */
    RUNTIME
}
