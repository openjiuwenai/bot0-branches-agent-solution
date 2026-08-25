/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import java.util.Map;

/**
 * Java code logic for {@code jiuwen.code} (explicit {@link com.openjiuwen.studio.dsl.registry.CodeLogicRegistry} registration).
 *
 * @since 2026-08-17
 */
public interface CodeLogic {
    /**
     * name.
     *
     * @return result
     */
    String name();

    /**
     * execute.
     *
     * @param inputs inputs
     * @param ctx ctx
     * @return result
     * @throws Exception when the call fails
     */
    Map<String, Object> execute(Map<String, Object> inputs, CodeLogicContext ctx) throws Exception;
}
