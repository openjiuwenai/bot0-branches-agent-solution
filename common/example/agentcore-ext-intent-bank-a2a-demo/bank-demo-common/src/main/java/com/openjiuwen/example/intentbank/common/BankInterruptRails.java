/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** User-input and confirmation rails used by transfer and wealth purchase agents. */
public final class BankInterruptRails {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> AFFIRMATIVE = Set.of("是", "确认", "同意", "继续", "yes", "y", "ok", "confirm");
    private static final Set<String> NEGATIVE = Set.of("否", "取消", "不同意", "no", "n", "cancel", "stop");

    private BankInterruptRails() {
    }

    /** Makes the standard ask_user Tool aware of a resumed request that changes intent. */
    public static final class IntentAwareAskUserRail extends BaseInterruptRail {
        public IntentAwareAskUserRail() {
            super(List.of("ask_user"));
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext context, ToolCall toolCall,
                Object resumeInput) {
            if (resumeInput == null) {
                return interrupt(InterruptRequest.builder().message(question(toolCall))
                        .context(Map.of("_interrupt_kind", "ask_user")).build());
            }
            String input = normalize(resumeInput);
            if (isNewIntent(input)) {
                return reject(intentChanged(input));
            }
            try {
                return approve(OBJECT_MAPPER.writeValueAsString(Map.of("response", input)));
            } catch (JsonProcessingException exception) {
                return reject(Map.of("status", "INVALID_INPUT", "message", "无法读取用户补充信息"));
            }
        }
    }

    /** Confirms a state-changing business tool before allowing its real invocation. */
    public static final class ConfirmationRail extends BaseInterruptRail {
        private final String confirmationMessage;

        public ConfirmationRail(String toolName, String confirmationMessage) {
            super(List.of(toolName));
            this.confirmationMessage = confirmationMessage;
        }

        @Override
        protected InterruptDecision resolveInterrupt(AgentCallbackContext context, ToolCall toolCall,
                Object resumeInput) {
            if (resumeInput == null) {
                return interrupt(InterruptRequest.builder().message(confirmationMessage)
                        .context(Map.of("_interrupt_kind", "ask_user")).build());
            }
            String input = normalize(resumeInput);
            if (isNewIntent(input)) {
                return reject(intentChanged(input));
            }
            if (AFFIRMATIVE.contains(input.toLowerCase(Locale.ROOT))) {
                return approve();
            }
            if (NEGATIVE.contains(input.toLowerCase(Locale.ROOT))) {
                return reject(Map.of("status", "CANCELLED", "message", "用户已取消操作"));
            }
            return interrupt(InterruptRequest.builder().message("请明确回复确认或取消。")
                    .context(Map.of("_interrupt_kind", "ask_user")).build());
        }
    }

    private static String question(ToolCall toolCall) {
        if (toolCall == null || toolCall.getArguments() == null) {
            return "请补充完成业务所需的信息。";
        }
        try {
            Map<?, ?> arguments = OBJECT_MAPPER.readValue(toolCall.getArguments(), Map.class);
            Object query = arguments.get("query");
            return query instanceof String text && !text.isBlank() ? text : "请补充完成业务所需的信息。";
        } catch (JsonProcessingException exception) {
            return "请补充完成业务所需的信息。";
        }
    }

    private static boolean isNewIntent(String input) {
        boolean wealthPurchase = input.contains("理财") && (input.contains("买") || input.contains("购买"));
        return input.contains("查余额") || input.contains("余额") || wealthPurchase || input.contains("理财推荐")
                || input.contains("天气") || input.contains("日期") || input.contains("几号") || input.contains("计算")
                || input.contains("转账") && input.length() > 8;
    }

    private static Map<String, Object> intentChanged(String input) {
        return Map.of("status", "INTENT_CHANGED", "latestSemantic", input, "message", "用户输入表达了新的处理目标");
    }

    private static String normalize(Object value) {
        return String.valueOf(value).trim();
    }
}
