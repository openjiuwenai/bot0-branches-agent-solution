/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

/** 未降维的 A2A Part。 */
sealed interface ProtocolPart permits ProtocolPart.Text, ProtocolPart.Data {
    record Text(String text) implements ProtocolPart {
    }

    record Data(Object data) implements ProtocolPart {
    }
}
