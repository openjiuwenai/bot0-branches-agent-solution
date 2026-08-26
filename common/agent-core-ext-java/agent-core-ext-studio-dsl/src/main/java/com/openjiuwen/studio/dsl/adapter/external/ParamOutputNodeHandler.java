/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EI.ParamOutput — strict 1:1 with Python {@code ParamOutput.py}.
 *
 * <p>Passthrough {@code userFields}; copy {@code systemFields} when present.
 * Missing {@code userFields} on a Map input → empty map (not the whole inputs).
 * Non-Map input → {@code {userFields: inputs}} as-is.
 *
 * @since 2026-08-25
 */
public final class ParamOutputNodeHandler implements NodeHandlerFactory {
    public static final String TYPE = "EI.ParamOutput";
    public static final String USER_FIELDS = "userFields";
    public static final String SYSTEM_FIELDS = "systemFields";

    @Override
    public String canonicalType() {
        return TYPE;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ei.paramOutput", "ei.ParamOutput");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new ParamOutputExecutable(node);
    }

    /** Python {@code ParamOutput.invoke} — map / non-map branches. */
    public static Map<String, Object> passthrough(Object inputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (inputs instanceof Map<?, ?> m) {
            if (m.containsKey(USER_FIELDS)) {
                result.put(USER_FIELDS, m.get(USER_FIELDS));
            } else {
                result.put(USER_FIELDS, Map.of());
            }
            Object systemFields = m.get(SYSTEM_FIELDS);
            if (systemFields != null) {
                result.put(SYSTEM_FIELDS, systemFields);
            }
            return result;
        }
        result.put(USER_FIELDS, inputs);
        return result;
    }

    static final class ParamOutputExecutable extends AbstractStudioNode {
        ParamOutputExecutable(AssembledNode node) {
            super(node);
        }

        /**
         * Bypass {@link AbstractStudioNode#asMap} so non-Map inputs match Python
         * {@code {USER_FIELDS: inputs}} rather than wrapping as {@code {value: ...}}.
         */
        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            return passthrough(inputs);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return NodePayload.ofFields(passthrough(inputs));
        }
    }
}
