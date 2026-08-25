/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/**
 * Java SPI for jiuwen.code (FEAT-031 MUST).
 *
 * @since 2026-08-17
 */
public interface CodeLogic {
    /**
     * name.
     */
    String name();
    /**
     * execute.
     * @param inputs inputs
     * @param ctx ctx
     * @throws Exception when the call fails
     */
    Map<String, Object> execute(Map<String, Object> inputs, CodeLogicContext ctx) throws Exception;
}
