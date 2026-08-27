/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

/**
 * Python {@code FlowApiStatusCode}.
 *
 * @since 2026-08-26
 */

public enum FlowApiStatusCode {
    SUCCESS(0, "success"),
    WORKFLOW_API_INIT_ERROR(101741, "Api component init error. msg={msg}"),
    WORKFLOW_API_PARAMS_CHECK_ERROR(101742, "Plugin flow components params check error"),
    WORKFLOW_API_INPUTS_ERROR(101743, "Plugin flow components input not defined, {msg}"),
    WORKFLOW_API_OUTPUTS_ERROR(101744, "Plugin flow components output is error"),
    WORKFLOW_API_EXECUTE_ERROR(101745, "Plugin flow components execute error");

    private final int code;
    private final String template;

    FlowApiStatusCode(int code, String template) {
        this.code = code;
        this.template = template;
    }

    /**
     * code.
     *
     * @return result
     * @since 0.1.0
     */

    public int code() {
        return code;
    }

    /**
     * template.
     *
     * @return result
     * @since 0.1.0
     */

    public String template() {
        return template;
    }
}
