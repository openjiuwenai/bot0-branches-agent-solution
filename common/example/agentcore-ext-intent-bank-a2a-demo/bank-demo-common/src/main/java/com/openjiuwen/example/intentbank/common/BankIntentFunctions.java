/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.model.ReturnAction;

import java.util.Map;

/**
 * Result functions referenced by the Intent Agent YAML configuration.
 *
 * @since 0.1.0
 */
public final class BankIntentFunctions {
    private BankIntentFunctions() {
    }

    /**
     * Creates a result function that invokes a Tool with the matched semantic as its query.
     *
     * @param toolName
     *            target Tool name
     *
     * @return intent result function
     */
    public static IntentResultFunction invokeWithQuery(String toolName) {
        return context -> new InvokeToolAction(toolName, Map.of("query", context.routingSemantic()));
    }

    /**
     * Creates the current-date Tool result function.
     *
     * @return intent result function
     */
    public static IntentResultFunction currentDate() {
        return context -> new InvokeToolAction(BankTools.CURRENT_DATE, Map.of());
    }

    /**
     * Creates the demo fallback result function.
     *
     * @return intent result function
     */
    public static IntentResultFunction fallback() {
        return context -> new ReturnAction(
                Map.of("handledBy", "intent-agent", "status", "UNMATCHED", "message", "未匹配到可执行的银行业务或本地能力，请补充说明。"));
    }
}
