/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.PathResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.extractor — deterministic field extraction by schema paths.
 * LLM-based extraction requires CoreExecutableFactory (optional); without it, path extract runs.
 *
 * @since 2026-08-17
 */
public final class ExtractorNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.extractor";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.infoExtraction");
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
        return new ExtractorExecutable(node, ctx);
    }

    static final class ExtractorExecutable extends AbstractStudioNode {
        private final NodeBuildContext ctx;

        ExtractorExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            this.ctx = ctx;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @throws Exception when the call fails
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            if (ctx.coreExecutableFactory() != null) {
                var coreOpt = ctx.coreExecutableFactory().createExtractor(node);
                if (coreOpt.isPresent()) {
                    Object out = coreOpt.get().invoke(inputs, session, context);
                    return NodePayload.ofFields(asMap(out));
                }
            }
            Map<String, Object> uf = userFieldsOf(inputs);
            Map<String, Object> extracted = new LinkedHashMap<>();
            Object fields = node.configs().getOrDefault("extractFields", node.configs().get("fields"));
            if (fields instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        String name = String.valueOf(first(m, "name", "id", ""));
                        String path = String.valueOf(pathOr(m, name));
                        extracted.put(name, PathResolver.get(uf, path).orElse(null));
                    } else if (item != null) {
                        String name = String.valueOf(item);
                        extracted.put(name, uf.get(name));
                    } else {
                        continue;
                    }
                }
            } else {
                if (fields instanceof Map<?, ?> map) {
                    map.forEach((k, v) ->
                            extracted.put(String.valueOf(k), PathResolver.get(uf, String.valueOf(v)).orElse(null)));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>(uf);
            out.putAll(extracted);
            out.put("extracted", extracted);
            return NodePayload.userFields(out);
        }

        private static Object first(Map<?, ?> m, String a, String b, Object def) {
            Object v = m.get(a);
            if (v == null) {
                v = m.get(b);
            }
            return v != null ? v : def;
        }

        private static String pathOr(Map<?, ?> m, String name) {
            Object v = m.get("path");
            if (v == null) {
                v = m.get("value");
            }
            return v != null ? String.valueOf(v) : name;
        }
    }
}
