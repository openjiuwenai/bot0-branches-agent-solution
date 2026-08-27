/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Format-style rails action (Python {@code FormatAction}).
 *
 * @since 2026-08-25
 */

public abstract class FormatAction extends RailsAction {

    /**
     * FormatAction.
     *
     * @param config config
     */

    protected FormatAction(ActionConfig config) {
        super(config);
    }

    /**
     * formatField.
     *
     * @param fieldName fieldName
     * @param value value
     * @return formatted
     */

    protected abstract Object formatField(String fieldName, Object value);

    @Override
    public Map<String, Object> execute(Map<String, Object> context) {
        Map<String, Object> args = ValidateAction.argsOf(context);
        Map<String, Object> formatted = new LinkedHashMap<>();
        for (String key : extraArgs.keySet()) {
            Object value = args.get(key);
            if (value == null) {
                formatted.put(key, null);
                continue;
            }
            formatted.put(key, formatField(key, value));
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!formatted.containsKey(e.getKey())) {
                formatted.put(e.getKey(), e.getValue());
            }
        }
        return wrapArgs(formatted);
    }
}
