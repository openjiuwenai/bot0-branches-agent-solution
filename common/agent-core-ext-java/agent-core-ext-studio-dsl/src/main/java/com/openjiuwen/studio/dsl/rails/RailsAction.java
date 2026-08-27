/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rails action base (Python {@code RailsAction}).
 *
 * @since 2026-08-25
 */

public abstract class RailsAction {
    /**
     * config.
     * @since 0.1.0
     */
    protected final ActionConfig config;
    protected final Map<String, Object> extraArgs;

    /**
     * RailsAction.
     *
     * @param config config
     */

    protected RailsAction(ActionConfig config) {
        this.config = config == null ? new ActionConfig(Map.of()) : config;
        this.extraArgs = this.config.actionExtraArgs();
    }

    /**
     * execute.
     *
     * @param context context (arguments, user_input, ...)
     * @return map with {@code arguments}
     */

    public abstract Map<String, Object> execute(Map<String, Object> context);

    /**
     * wrapArgs.
     *
     * @param args args
     * @return result
     */

    protected static Map<String, Object> wrapArgs(Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arguments", args == null ? Map.of() : args);
        return out;
    }
}
