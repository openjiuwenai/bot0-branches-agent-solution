/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.component.BranchComponent;
import com.openjiuwen.core.workflow.condition.AlwaysTrue;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.ConditionEvaluator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * jiuwen.branch — populate core BranchComponent/BranchRouter and select branchId (FEAT routing).
 *
 * <p>Core {@code Branch} only accepts Condition / String / BooleanSupplier — not Function.
 *
 * @since 2026-08-17
 */
public final class BranchNodeHandler implements NodeHandlerFactory {

    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.branch";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        BranchComponent component = new BranchComponent();
        List<BranchDef> defs = parse(node.configs());
        BranchExecutable executable = new BranchExecutable(node, component, defs);
        for (BranchDef d : defs) {
            Object cond = toCoreCondition(d, executable);
            List<String> targets = d.targets.isEmpty() ? List.of(d.branchId) : d.targets;
            component.addBranch(cond, targets, d.branchId);
        }
        return executable;
    }

    /**
     * Map Studio condition shapes onto types Branch accepts.
     *
     * @return result
     */
    static Object toCoreCondition(BranchDef d, BranchExecutable executable) {
        if (d.isDefault) {
            return new AlwaysTrue();
        }
        if (d.condition instanceof String s) {
            return s;
        }
        return (BooleanSupplier) () -> ConditionEvaluator.matches(d.condition, executable.lastUserFields());
    }

    static final class BranchExecutable extends AbstractStudioNode {
        private final BranchComponent component;
        private final List<BranchDef> defs;
        private volatile Map<String, Object> lastUserFields = Map.of();

        BranchExecutable(AssembledNode node, BranchComponent component, List<BranchDef> defs) {
            super(node);
            this.component = component;
            this.defs = defs;
        }

        Map<String, Object> lastUserFields() {
            return lastUserFields;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = userFieldsOf(inputs);
            lastUserFields = uf;
            String selected = select(defs, uf);
            Map<String, Object> outUf = new LinkedHashMap<>(uf);
            outUf.put("__branchId__", selected);
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("userFields", outUf);
            wrap.put("branchId", selected);
            wrap.put("routeTargets", List.of(selected));
            return NodePayload.ofFields(wrap);
        }

        /**
         * branchComponent.
         *
         * @return result
         */
        public BranchComponent branchComponent() {
            return component;
        }
    }

    static String select(List<BranchDef> defs, Map<String, Object> uf) {
        String fallback = "default";
        for (BranchDef d : defs) {
            if (d.isDefault) {
                fallback = d.branchId;
                continue;
            }
            if (ConditionEvaluator.matches(d.condition, uf)) {
                return d.branchId;
            }
        }
        return fallback;
    }

    static List<BranchDef> parse(Map<String, Object> configs) {
        List<BranchDef> out = new ArrayList<>();
        Object branches = configs.get("branches");
        if (branches instanceof List<?> list) {
            for (Object item : list) {
                addParsed(out, item);
            }
        }
        if (out.isEmpty()) {
            out.add(new BranchDef("default", null, true, List.of("default")));
        }
        return out;
    }

    private static void addParsed(List<BranchDef> out, Object item) {
        if (!(item instanceof Map<?, ?> m)) {
            return;
        }
        String id = String.valueOf(first(m, "branchId", "id", "default"));
        Object cond = firstObj(m, "condition", "conditions");
        boolean isDefault = Boolean.TRUE.equals(m.get("isDefault"))
                || "default".equalsIgnoreCase(id)
                || cond == null;
        out.add(new BranchDef(id, cond, isDefault, targetIds(m.get("targets"))));
    }

    private static List<String> targetIds(Object t) {
        List<String> targets = new ArrayList<>();
        if (t instanceof List<?> tl) {
            for (Object x : tl) {
                targets.add(String.valueOf(x));
            }
        }
        return targets;
    }

    private static Object firstObj(Map<?, ?> m, String a, String b) {
        Object v = m.get(a);
        return v != null ? v : m.get(b);
    }

    private static Object first(Map<?, ?> m, String a, String b, Object def) {
        Object v = firstObj(m, a, b);
        return v != null ? v : def;
    }

    static final class BranchDef {
        final String branchId;
        final Object condition;
        final boolean isDefault;
        final List<String> targets;

        BranchDef(String branchId, Object condition, boolean isDefault, List<String> targets) {
            this.branchId = branchId;
            this.condition = condition;
            this.isDefault = isDefault;
            this.targets = targets;
        }
    }
}
