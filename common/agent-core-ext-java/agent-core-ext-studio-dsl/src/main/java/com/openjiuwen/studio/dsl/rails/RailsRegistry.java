/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.rails;

import com.openjiuwen.studio.dsl.rails.formatters.DateTimeFormatValidateAction;
import com.openjiuwen.studio.dsl.rails.validators.CommonDataFormatCheckAction;
import com.openjiuwen.studio.dsl.rails.validators.EnumLegalityValidateAction;
import com.openjiuwen.studio.dsl.rails.validators.LengthLimitValidateAction;
import com.openjiuwen.studio.dsl.rails.validators.NumberRangeValidateAction;
import com.openjiuwen.studio.dsl.rails.validators.TimeParseAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Rails action registry + executor (Python {@code RailsRegistry} / {@code execute_rails}).
 *
 * @since 2026-08-25
 */
public final class RailsRegistry {
    private static final RailsRegistry INSTANCE = new RailsRegistry();

    private final Map<String, Function<ActionConfig, RailsAction>> factories = new ConcurrentHashMap<>();

    private RailsRegistry() {
        register("enum_legality_validate", EnumLegalityValidateAction::new);
        register("length_limit_validate", LengthLimitValidateAction::new);
        register("number_range_validate", NumberRangeValidateAction::new);
        register("common_data_format_check", CommonDataFormatCheckAction::new);
        register("date_time_format", DateTimeFormatValidateAction::new);
        register("parse_time", TimeParseAction::new);
    }

    /**
     * getInstance.
     *
     * @return result
     */
    public static RailsRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * register.
     *
     * @param name name
     * @param factory factory
     */
    public void register(String name, Function<ActionConfig, RailsAction> factory) {
        factories.put(name, factory);
    }

    /**
     * createAction.
     *
     * @param name name
     * @param extraArgs extraArgs
     * @return result
     */
    public RailsAction createAction(String name, Map<String, Object> extraArgs) {
        Function<ActionConfig, RailsAction> f = factories.get(name);
        if (f == null) {
            return null;
        }
        return f.apply(new ActionConfig(extraArgs));
    }

    /**
     * Execute railsConfig against arguments (sync; Python was async but actions are sync).
     *
     * @param railsConfig railsConfig ({@code rails.execution} + {@code actions_config})
     * @param context context with arguments / user_input
     * @return updated arguments
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> executeRails(Map<String, Object> railsConfig, Map<String, Object> context) {
        if (railsConfig == null || railsConfig.isEmpty()) {
            return ValidateAction.argsOf(context);
        }
        Map<String, Object> actionExtraArgsMap = new LinkedHashMap<>();
        Object actionsConfig = railsConfig.get("actions_config");
        if (actionsConfig instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object action = m.get("action");
                    Object extra = m.get("action_extra_args");
                    if (action != null && extra instanceof Map<?, ?> em) {
                        Map<String, Object> cast = new LinkedHashMap<>();
                        em.forEach((k, v) -> cast.put(String.valueOf(k), v));
                        actionExtraArgsMap.put(String.valueOf(action), cast);
                    }
                }
            }
        }
        Object rails = railsConfig.get("rails");
        List<?> execution = List.of();
        if (rails instanceof Map<?, ?> rm) {
            Object exec = rm.get("execution");
            if (exec instanceof List<?> el) {
                execution = el;
            }
        }
        Map<String, Object> args = ValidateAction.argsOf(context);
        RailsRegistry registry = getInstance();
        for (Object rail : execution) {
            if (!(rail instanceof Map<?, ?> rm)) {
                continue;
            }
            Object actionName = rm.get("action");
            if (actionName == null) {
                continue;
            }
            String name = String.valueOf(actionName);
            Map<String, Object> extra =
                    (Map<String, Object>) actionExtraArgsMap.getOrDefault(name, Map.of());
            RailsAction action = registry.createAction(name, extra);
            if (action == null) {
                continue;
            }
            Map<String, Object> actionContext = new LinkedHashMap<>(context == null ? Map.of() : context);
            actionContext.put("arguments", args);
            Map<String, Object> result = action.execute(actionContext);
            Object next = result.get("arguments");
            if (next instanceof Map<?, ?> nm) {
                Map<String, Object> nextArgs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : nm.entrySet()) {
                    nextArgs.put(String.valueOf(e.getKey()), e.getValue());
                }
                args = nextArgs;
            }
        }
        return args;
    }
}
