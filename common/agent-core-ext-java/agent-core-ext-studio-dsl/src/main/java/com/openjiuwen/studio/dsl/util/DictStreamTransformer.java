/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sync port of Python {@code AsyncDictStreamTransformer}.
 *
 * @since 2026-08-25
 */
public final class DictStreamTransformer {
    private static final Pattern TEMPLATE_VAR_RE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");
    private static final Object TEMPLATE_MISSING = new Object();

    private final DictStreamTransformConfig cfg;
    private final List<DictStreamTransformConfig.VariableDef> concatVars;
    private final Map<String, List<String>> concatBuffers = new LinkedHashMap<>();
    private final Map<String, Object> lastVarValues = new LinkedHashMap<>();

    /**
     * DictStreamTransformer.
     *
     * @param config config
     */
    public DictStreamTransformer(DictStreamTransformConfig config) {
        this.cfg = config;
        this.concatVars = config.variables().stream().filter(DictStreamTransformConfig.VariableDef::concat).toList();
        for (DictStreamTransformConfig.VariableDef v : concatVars) {
            concatBuffers.put(v.name(), new ArrayList<>());
        }
    }

    /**
     * Transform input dict frames into output dict frames (lookahead for {@code is_last}).
     *
     * @param stream input frames
     * @return output frames
     */
    public List<Map<String, Object>> transform(Iterable<Map<String, Object>> stream) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> prevOut = null;

        for (Map<String, Object> inFrame : stream) {
            Map<String, Object> built = buildFrameFromTemplate(inFrame);
            if (prevOut != null) {
                if (cfg.addIsLast()) {
                    prevOut.put(cfg.isLastField(), false);
                }
                out.add(prevOut);
            }
            prevOut = built;
        }

        if (prevOut == null) {
            return out;
        }

        boolean hasConcat = !concatBuffers.isEmpty();
        if (hasConcat && cfg.emitFinalConcatFrame()) {
            if (cfg.addIsLast()) {
                prevOut.put(cfg.isLastField(), false);
            }
            out.add(prevOut);
            out.add(buildFinalExtraFrame());
            return out;
        }

        if (cfg.addIsLast()) {
            prevOut.put(cfg.isLastField(), true);
        }
        out.add(prevOut);
        return out;
    }

    private Object readSrc(Map<String, Object> frame, String srcPath, Object defaultValue) {
        Object base = frame;
        if (cfg.inputRootPath() != null && !cfg.inputRootPath().isEmpty()) {
            base = DictStreamPath.getByPath(frame, cfg.inputRootPath(), Map.of());
        }
        return DictStreamPath.getByPath(base, srcPath, defaultValue);
    }

    private void accumulateConcat(String key, Object value, boolean skipNone, boolean castToStr) {
        if (skipNone && value == null) {
            return;
        }
        if (value == null) {
            return;
        }
        Object v = value;
        if (castToStr && !(v instanceof String)) {
            v = String.valueOf(v);
        }
        concatBuffers.get(key).add(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFrameFromTemplate(Map<String, Object> inFrame) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (DictStreamTransformConfig.VariableDef vdef : cfg.variables()) {
            Object v = readSrc(inFrame, vdef.srcPath(), vdef.defaultValue());
            values.put(vdef.name(), v);
            if (v != null) {
                lastVarValues.put(vdef.name(), v);
            }
            if (vdef.concat()) {
                accumulateConcat(vdef.name(), v, vdef.skipNone(), vdef.castToStrForConcat());
            }
        }

        Object rendered = renderJsonTemplate(cfg.frameTemplate(), values);
        if (cfg.pruneNoneFields()) {
            rendered = pruneNoneFields(rendered);
        }
        if (!(rendered instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("frame_template must render to a dict");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) rendered).forEach((k, v) -> out.put(String.valueOf(k), v));
        if (cfg.addIsLast()) {
            out.put(cfg.isLastField(), false);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFinalExtraFrame() {
        Map<String, Object> finalValues = new LinkedHashMap<>(lastVarValues);
        for (DictStreamTransformConfig.VariableDef vdef : concatVars) {
            List<String> parts = concatBuffers.getOrDefault(vdef.name(), List.of());
            finalValues.put(vdef.name(), String.join(vdef.concatSep(), parts));
        }

        Object tpl = cfg.finalTemplate() != null ? cfg.finalTemplate() : cfg.frameTemplate();
        Object rendered = renderJsonTemplate(tpl, finalValues);
        if (cfg.pruneNoneFields()) {
            rendered = pruneNoneFields(rendered);
        }
        if (!(rendered instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("final_template must render to a dict");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) rendered).forEach((k, v) -> out.put(String.valueOf(k), v));
        if (cfg.addIsLast()) {
            out.put(cfg.isLastField(), true);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object renderJsonTemplate(Object template, Map<String, Object> variables) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), renderJsonTemplate(v, variables)));
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(renderJsonTemplate(v, variables));
            }
            return out;
        }
        if (template instanceof String s) {
            Matcher full = TEMPLATE_VAR_RE.matcher(s);
            if (full.matches()) {
                String name = full.group(1);
                return variables.containsKey(name) ? variables.get(name) : TEMPLATE_MISSING;
            }
            Matcher m = TEMPLATE_VAR_RE.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                Object val = variables.get(name);
                String repl = (val == null || val == TEMPLATE_MISSING) ? "" : String.valueOf(val);
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    static Object pruneNoneFields(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object pv = pruneNoneFields(e.getValue());
                if (pv == TEMPLATE_MISSING) {
                    continue;
                }
                if (pv instanceof Map<?, ?> nested && nested.isEmpty()) {
                    continue;
                }
                out.put(String.valueOf(e.getKey()), pv);
            }
            return out;
        }
        if (obj instanceof List<?> list) {
            return list.stream()
                    .map(v -> {
                        Object pv = pruneNoneFields(v);
                        return pv == TEMPLATE_MISSING ? null : pv;
                    })
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        return obj;
    }
}
