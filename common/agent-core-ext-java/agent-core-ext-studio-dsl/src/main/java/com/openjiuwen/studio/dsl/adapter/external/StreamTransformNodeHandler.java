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
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.streamTransform — field include/exclude/mapping on invoke + transform.
 *
 * @since 2026-08-17
 */
public final class StreamTransformNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.streamTransform";
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
        return new StreamTransformExecutable(node);
    }

    static final class StreamTransformExecutable extends AbstractStudioNode {
        StreamTransformExecutable(AssembledNode node) {
            super(node);
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
            return NodePayload.userFields(transformFields(userFieldsOf(inputs), node.configs()));
        }

        /**
         * transform.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         */
        @Override
        public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> in = asMap(inputs);
            Map<String, Object> out = transformFields(userFieldsOf(in), node.configs());
            return Collections.singletonList((Object) NodePayload.userFields(out).toInvokeMap()).iterator();
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> transformFields(Map<String, Object> uf, Map<String, Object> configs) {
            Map<String, Object> out = new LinkedHashMap<>();
            Object include = configs.get("includeFields");
            Object exclude = configs.get("excludeFields");
            Object mapping = configs.getOrDefault("fieldMapping", configs.get("mapping"));
            if (include instanceof List<?> list && !list.isEmpty()) {
                for (Object k : list) {
                    String key = String.valueOf(k);
                    if (uf.containsKey(key)) {
                        out.put(key, uf.get(key));
                    }
                }
            } else {
                out.putAll(uf);
            }
            if (exclude instanceof List<?> list) {
                for (Object k : list) {
                    out.remove(String.valueOf(k));
                }
            }
            if (mapping instanceof Map<?, ?> map) {
                Map<String, Object> remapped = new LinkedHashMap<>();
                map.forEach((from, to) -> {
                    if (out.containsKey(String.valueOf(from))) {
                        remapped.put(String.valueOf(to), out.get(String.valueOf(from)));
                    }
                });
                if (!remapped.isEmpty()) {
                    out.putAll(remapped);
                }
            }
            return out;
        }
    }
}
