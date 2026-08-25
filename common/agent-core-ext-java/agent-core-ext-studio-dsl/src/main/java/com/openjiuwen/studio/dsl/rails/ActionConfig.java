/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rails action_extra_args wrapper (Python {@code ActionConfig}).
 *
 * @since 2026-08-25
 */
public final class ActionConfig {
    private final Map<String, Object> actionExtraArgs;

    /**
     * ActionConfig.
     *
     * @param actionExtraArgs actionExtraArgs
     */
    public ActionConfig(Map<String, Object> actionExtraArgs) {
        this.actionExtraArgs =
                actionExtraArgs == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(actionExtraArgs));
    }

    /**
     * actionExtraArgs.
     *
     * @return result
     */
    public Map<String, Object> actionExtraArgs() {
        return actionExtraArgs;
    }

    /**
     * getExtraArg.
     *
     * @param key key
     * @param defaultValue defaultValue
     * @return result
     */
    public Object getExtraArg(String key, Object defaultValue) {
        return actionExtraArgs.getOrDefault(key, defaultValue);
    }
}
