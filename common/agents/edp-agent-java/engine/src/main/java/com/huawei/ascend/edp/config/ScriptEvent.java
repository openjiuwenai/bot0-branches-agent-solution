/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.config;

/**
 * 脚本事件枚举，用于标记特定话术场景（对应需求文档 §A 的五个特定事件）。
 *
 * @since 2024-01-01
  *
 */

public enum ScriptEvent {
    REQUEST_START("request_start"), PLANNING_START("planning_start"), TASK_CANCELLED("task_cancelled"), CANCEL_CONFIRM(
            "cancel_confirm"), OUT_OF_SCOPE("out_of_scope");

    private final String key;

    ScriptEvent(String key) {
        this.key = key;
    }

    /**
     * Gets the key.
     */
    public String getKey() {
        return key;
    }
}
