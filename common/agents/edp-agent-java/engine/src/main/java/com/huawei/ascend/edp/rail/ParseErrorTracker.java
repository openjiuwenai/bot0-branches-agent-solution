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

package com.huawei.ascend.edp.rail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 输出 JSON 解析失败追踪器。
 *
 * <p>功能：</p>
 * <ul>
 *     <li>累计解析失败次数，达到阈值后升级为 ERROR 级别告警日志</li>
 *     <li>提供 {@link #MARKER_KEY} 标记键，解析失败时返回带此标记的降级 Map</li>
 *     <li>上层调用方可通过 {@link #hasParseError(Map)} 检查是否为降级结果</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public final class ParseErrorTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseErrorTracker.class);

    /** 降级 Map 中的标记键名 */
    public static final String MARKER_KEY = "__parse_error__";

    /** 累计告警阈值：超过此值后日志升级为 ERROR */
    private static final int ALERT_THRESHOLD = 10;

    /** 全局解析失败计数器（进程级） */
    private static final AtomicInteger TOTAL_FAILURES = new AtomicInteger(0);

    private ParseErrorTracker() {
    }

    /**
     * 记录一次解析失败，递增全局计数器并输出日志。
     *
     * @param source 解析失败来源标识（如类名:方法名）
     * @param errorMsg 错误信息
     */
    public static void recordFailure(String source, String errorMsg) {
        int count = TOTAL_FAILURES.incrementAndGet();
        if (count >= ALERT_THRESHOLD && count % ALERT_THRESHOLD == 0) {
            LOGGER.error("[ParseErrorTracker] LLM output JSON parse failure accumulated: count={}, source={}, err={}",
                    count, source, errorMsg);
        } else {
            LOGGER.warn("[ParseErrorTracker] parse failed (count={}), source={}, err={}",
                    count, source, errorMsg);
        }
    }

    /**
     * 创建带 parse_error 标记的降级 Map。
     *
     * <p>调用方通过 {@link #hasParseError(Map)} 检查返回值是否为降级结果，
     * 并据此触发重试或显式降级逻辑。</p>
     *
     * @param errorMsg 解析错误信息
     * @return 包含 {@link #MARKER_KEY} 的非空 Map
     */
    public static Map<String, Object> degradedMap(String errorMsg) {
        Map<String, Object> map = new LinkedHashMap<>(1);
        map.put(MARKER_KEY, errorMsg);
        return map;
    }

    /**
     * 检查 Map 是否包含解析错误标记。
     *
     * @param map the map to check
     * @return true if the map contains {@link #MARKER_KEY}
     */
    public static boolean hasParseError(Map<String, ?> map) {
        return map != null && map.containsKey(MARKER_KEY);
    }

    /**
     * 获取全局解析失败总次数。
     *
     * @return the total failure count
     */
    public static int getTotalFailures() {
        return TOTAL_FAILURES.get();
    }
}
