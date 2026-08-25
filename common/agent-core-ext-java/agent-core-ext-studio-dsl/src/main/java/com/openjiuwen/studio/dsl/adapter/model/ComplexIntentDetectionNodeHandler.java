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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EI.ComplexIntentDetection — branch selection by keyword / catalog match.
 *
 * <p>Full Python path (nested SubWorkflow + LLM IntentDetection) deferred; this ports
 * the branch-routing contract: {@code classificationId} / {@code branch_id} / userFields agg.
 *
 * @since 2026-08-25
 */
public final class ComplexIntentDetectionNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "EI.ComplexIntentDetection";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ei.complexIntentDetection", "EI.complexIntentDetection");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new ComplexIntentExecutable(node);
    }

    static final class ComplexIntentExecutable extends AbstractStudioNode {
        ComplexIntentExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            String query = String.valueOf(uf.getOrDefault("input", uf.getOrDefault("query", ""))).toLowerCase(Locale.ROOT);

            List<Map<String, Object>> branches = branchesOf(node.configs());
            Map<String, Object> matched = null;
            double score = 0.0;
            for (Map<String, Object> b : branches) {
                String catalog = String.valueOf(b.getOrDefault("catalog", "")).toLowerCase(Locale.ROOT);
                if (!catalog.isBlank() && !query.isBlank() && query.contains(catalog)) {
                    matched = b;
                    score = 1.0;
                    break;
                }
            }
            if (matched == null && !branches.isEmpty()) {
                matched = branches.get(0);
                score = query.isBlank() ? 0.0 : 0.1;
            }

            Map<String, Object> out = new LinkedHashMap<>(uf);
            if (matched != null) {
                String branchId = String.valueOf(matched.getOrDefault("id", ""));
                out.put("classificationId", branchId);
                out.put("branch_id", branchId);
                out.put("matched_catalog", matched.get("catalog"));
                out.put("matched_score", score);
                Object configs = matched.get("configs");
                if (configs instanceof Map<?, ?> cm) {
                    Object wf = cm.get("workflow_id");
                    if (wf == null) {
                        wf = cm.get("ir_path");
                    }
                    if (wf != null) {
                        out.put("workflow_id", wf);
                    }
                }
            } else {
                out.put("classificationId", "");
                out.put("branch_id", "");
                out.put("matched_score", 0.0);
            }
            out.put("complexIntentState", "end");
            // groups / agg_mode: first-non-null soft merge from uf when groups map present
            Object groups = node.configs().get("groups");
            if (groups instanceof Map<?, ?> gmap) {
                String mode = String.valueOf(node.configs().getOrDefault("agg_mode", "first-non-null"));
                if ("first-non-null".equals(mode)) {
                    gmap.forEach((k, v) -> {
                        if (v instanceof List<?> keys) {
                            for (Object key : keys) {
                                Object val = uf.get(String.valueOf(key));
                                if (val instanceof String s) {
                                    if (!s.isEmpty()) {
                                        out.put(String.valueOf(k), val);
                                        break;
                                    }
                                } else if (val != null) {
                                    out.put(String.valueOf(k), val);
                                    break;
                                }
                            }
                        }
                    });
                }
            }
            return NodePayload.userFields(out);
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> branchesOf(Map<String, Object> configs) {
            Object raw = configs.get("branches");
            List<Map<String, Object>> out = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> b = new LinkedHashMap<>();
                        m.forEach((k, v) -> b.put(String.valueOf(k), v));
                        out.add(b);
                    }
                }
            }
            return out;
        }
    }
}
