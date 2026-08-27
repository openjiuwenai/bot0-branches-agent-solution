/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import java.util.List;
import java.util.Map;

/**
 * Compatibility facade — delegates to {@link com.openjiuwen.studio.dsl.flowinput.FlowInputUtils}.
 *
 * @since 2026-08-25
 */

public final class FlowInputUtils {
    private FlowInputUtils() {}

    /**
     * buildInputsMessage.
     *
     * @param config config
     * @return result
     * @since 0.1.0
     */

    public static String buildInputsMessage(Map<String, Object> config) {
        return com.openjiuwen.studio.dsl.flowinput.FlowInputUtils.buildInputsMessage(config);
    }
    public static Map<String, Object> parseUserResponse(Object userResponse) {
        return com.openjiuwen.studio.dsl.flowinput.FlowInputUtils.parseUserResponse(userResponse);
    }

    /**
     * validateInputs.
     *
     * @param inputs inputs
     * @param config config
     * @since 0.1.0
     */

    public static void validateInputs(Map<String, Object> inputs, Map<String, Object> config) {
        com.openjiuwen.studio.dsl.flowinput.FlowInputUtils.validateInputs(inputs, config);
    }
    public static Map<String, Object> fillInputs(
            Map<String, Object> inputs, Map<String, Object> values, Map<String, Object> config) {
        return com.openjiuwen.studio.dsl.flowinput.FlowInputUtils.fillInputs(inputs, values, config);
    }

    public static List<Map<String, Object>> inputDefs(Map<String, Object> config) {
        return com.openjiuwen.studio.dsl.flowinput.FlowInputUtils.inputDefs(config);
    }
}
