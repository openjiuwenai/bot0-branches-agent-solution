package com.openjiuwen.studio.dsl.adapter.model;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.util.PathResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.extractor — deterministic field extraction by schema paths.
 * LLM-based extraction requires CoreExecutableFactory (optional); without it, path extract runs.
 */
public final class ExtractorNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.extractor";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.infoExtraction");
    }

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

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            if (ctx.coreExecutableFactory() != null) {
                ComponentExecutable core = ctx.coreExecutableFactory().createExtractor(node);
                if (core != null) {
                    Object out = core.invoke(inputs, session, context);
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
                        extracted.put(name, PathResolver.get(uf, path));
                    } else if (item != null) {
                        String name = String.valueOf(item);
                        extracted.put(name, uf.get(name));
                    }
                }
            } else if (fields instanceof Map<?, ?> map) {
                map.forEach((k, v) -> extracted.put(String.valueOf(k), PathResolver.get(uf, String.valueOf(v))));
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

        private static Object pathOr(Map<?, ?> m, String name) {
            Object v = m.get("path");
            if (v == null) {
                v = m.get("value");
            }
            return v != null ? v : name;
        }
    }
}
