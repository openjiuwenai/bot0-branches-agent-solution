/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.agents.intent.spi.IntentResultFunction;
import com.openjiuwen.agents.intent.model.FinishAction;
import com.openjiuwen.agents.intent.model.InvokeToolAction;

import java.util.Map;

/**
 * Result functions referenced by the Intent Agent YAML configuration.
 *
 * @since 0.1.0
 */
public final class BankIntentFunctions {
    private static final String FALLBACK_REPLY = "未匹配到可执行的银行业务或本地能力，请补充说明。";

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
     * <p>
     * 兜底话术就是要回给用户的最终答复，所以返回 {@link FinishAction} 直接结束本轮，避免模型改写或忽略这句拒绝。
     *
     * @return intent result function
     */
    public static IntentResultFunction fallback() {
        return context -> new FinishAction(
                Map.of("handledBy", "intent-agent", "status", "UNMATCHED", "message", FALLBACK_REPLY), FALLBACK_REPLY);
    }
}
