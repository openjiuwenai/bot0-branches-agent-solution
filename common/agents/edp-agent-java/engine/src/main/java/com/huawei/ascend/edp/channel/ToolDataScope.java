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

package com.huawei.ascend.edp.channel;

/**
 * ToolDataChannel 作用域定义。
 *
 * 定义数据在通道中的作用域范围。
 *
 * @since 2024-01-01
 */

public enum ToolDataScope {
    /** 单次工具调用作用域，调用结束后自动清理。 */
    SINGLE_CALL,
    /** 单轮 ReAct 作用域，轮结束后自动清理。 */
    SINGLE_ROUND,
    /** 整个任务作用域，任务完成后清理。 */
    TASK,
    /** 持久作用域，需要手动清理。 */
    PERSISTENT
}
