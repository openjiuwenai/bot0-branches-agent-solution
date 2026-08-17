/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/**
 * 根调用当前所处的发言阶段。
 *
 * @since 2026-07-27
 */
public enum SpeakingPhase {
    ROOT_SPEAKING,
    DESCENDANT_SPEAKING,
    WAITING_DESCENDANTS,
    UNKNOWN
}
