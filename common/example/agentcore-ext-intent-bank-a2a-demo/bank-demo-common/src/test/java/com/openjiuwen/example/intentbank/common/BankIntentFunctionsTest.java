/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.initializer.DefaultIntentInitializer;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDecision;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.InvokeToolAction;
import com.openjiuwen.agents.intent.model.ReturnAction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class BankIntentFunctionsTest {
    @Test
    void localFunctionForwardsRoutingSemanticAsQuery() {
        IntentDecision decision = resolve(BankIntentFunctions.invokeWithQuery(BankTools.WEATHER), "深圳天气怎么样");

        assertThat(decision.action()).isInstanceOfSatisfying(InvokeToolAction.class, action -> {
            assertThat(action.toolName()).isEqualTo(BankTools.WEATHER);
            assertThat(action.arguments()).containsEntry("query", "深圳天气怎么样");
        });
    }

    @Test
    void dateFunctionNeedsNoModelGeneratedArguments() {
        IntentDecision decision = resolve(BankIntentFunctions.currentDate(), "今天几号");

        assertThat(decision.action()).isEqualTo(new InvokeToolAction(BankTools.CURRENT_DATE, Map.of()));
    }

    @Test
    void fallbackReturnsStableUnmatchedPayload() {
        IntentDecision decision = resolve(BankIntentFunctions.fallback(), "写一首诗");

        assertThat(decision.action()).isInstanceOfSatisfying(ReturnAction.class,
                action -> assertThat(payload(action.result())).containsEntry("status", "UNMATCHED"));
    }

    private static IntentDecision resolve(IntentResultFunction function, String semantic) {
        IntentSuite suite = IntentSuite.builder(IntentSuiteConfig.defaults())
                .initializer(new DefaultIntentInitializer()).matcher(context -> Optional
                        .of(context.catalogSnapshot().initializedIntents().matchableIntents().get(0)))
                .build();
        suite.replaceCatalog(new IntentCatalogInput(List.of(),
                List.of(new CustomIntentRegistration("test", "test intent", function)), null));
        return suite.resolve(Map.of("semantic", semantic), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Object value) {
        return (Map<String, Object>) value;
    }
}
