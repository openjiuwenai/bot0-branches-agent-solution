/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Config for {@link DictStreamTransformer} (Python {@code AsyncDictStreamTransformConfig}).
 *
 * @since 2026-08-25
 */

public final class DictStreamTransformConfig {
    private final String inputRootPath;
    private final List<VariableDef> variables;
    private final Object frameTemplate;
    private final Object finalTemplate;
    private final boolean pruneNoneFields;
    private final boolean addIsLast;
    private final String isLastField;
    private final boolean emitFinalConcatFrame;

    /**
     * DictStreamTransformConfig.
     *
     * @param inputRootPath inputRootPath
     * @param variables variables
     * @param frameTemplate frameTemplate
     * @param finalTemplate finalTemplate
     * @param pruneNoneFields pruneNoneFields
     * @param addIsLast addIsLast
     * @param isLastField isLastField
     * @param emitFinalConcatFrame emitFinalConcatFrame
     */

    public DictStreamTransformConfig(
            String inputRootPath,
            List<VariableDef> variables,
            Object frameTemplate,
            Object finalTemplate,
            boolean pruneNoneFields,
            boolean addIsLast,
            String isLastField,
            boolean emitFinalConcatFrame) {
        this.inputRootPath = inputRootPath;
        this.variables = Collections.unmodifiableList(new ArrayList<>(variables == null ? List.of() : variables));
        this.frameTemplate = frameTemplate;
        this.finalTemplate = finalTemplate;
        this.pruneNoneFields = pruneNoneFields;
        this.addIsLast = addIsLast;
        this.isLastField = isLastField;
        this.emitFinalConcatFrame = emitFinalConcatFrame;
    }

    /**
     * Parse from a JSON/dict object (snake_case keys, matching Python).
     *
     * @param data data
     * @return config
     */

    @SuppressWarnings("unchecked")
    public static DictStreamTransformConfig fromDict(Map<String, Object> data) {
        if (data == null) {
        throw new IllegalArgumentException("AsyncDictStreamTransformConfig must be a dict");
    }
        Object variablesRaw = data.getOrDefault("variables", List.of());
        if (variablesRaw == null) {
            variablesRaw = List.of();
        }
        if (!(variablesRaw instanceof List<?>)) {
            throw new IllegalArgumentException("AsyncDictStreamTransformConfig.variables must be a list");
        }
        List<VariableDef> variables = new ArrayList<>();
        for (Object x : (List<?>) variablesRaw) {
            if (!(x instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("VariableDef must be a dict");
            }
            Map<String, Object> vd = new LinkedHashMap<>();
            ((Map<?, ?>) x).forEach((k, v) -> vd.put(String.valueOf(k), v));
            variables.add(VariableDef.fromDict(vd));
        }

        Object frameTemplate = data.get("frame_template");
        if (frameTemplate == null) {
            throw new IllegalArgumentException("AsyncDictStreamTransformConfig.frame_template is required");
        }
        Object finalTemplate = data.get("final_template");
        boolean pruneNoneFields = bool(data.get("prune_none_fields"), false);

        Object inputRootPathObj = data.get("input_root_path");
        String inputRootPath = inputRootPathObj == null ? null : String.valueOf(inputRootPathObj);

        Object isLastFieldObj = data.getOrDefault("is_last_field", "is_last");
        if (!(isLastFieldObj instanceof String) || ((String) isLastFieldObj).isEmpty()) {
            throw new IllegalArgumentException(
                    "AsyncDictStreamTransformConfig.is_last_field must be a non-empty string");
        }

        return new DictStreamTransformConfig(
                inputRootPath,
                variables,
                frameTemplate,
                finalTemplate,
                pruneNoneFields,
                bool(data.get("add_is_last"), true),
                (String) isLastFieldObj,
                bool(data.get("emit_final_concat_frame"), true));
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) {
        return def;
    }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** @return inputRootPath */
    public String inputRootPath() {
        return inputRootPath;
    }

    /** @return variables */
    public List<VariableDef> variables() {
        return variables;
    }

    /** @return frameTemplate */
    public Object frameTemplate() {
        return frameTemplate;
    }

    /** @return finalTemplate */
    public Object finalTemplate() {
        return finalTemplate;
    }

    /** @return pruneNoneFields */
    public boolean pruneNoneFields() {
        return pruneNoneFields;
    }

    /** @return addIsLast */
    public boolean addIsLast() {
        return addIsLast;
    }

    /** @return isLastField */
    public String isLastField() {
        return isLastField;
    }

    /** @return emitFinalConcatFrame */
    public boolean emitFinalConcatFrame() {
        return emitFinalConcatFrame;
    }

    /**
     * Variable definition used by JSON templates.
     *
     * @since 2026-08-25
     */

    public static final class VariableDef {
        private final String name;
        private final String srcPath;
        private final Object defaultValue;
        private final boolean concat;
        private final String concatSep;
        private final boolean skipNone;
        private final boolean castToStrForConcat;

        /**
         * VariableDef.
         *
         * @param name name
         * @param srcPath srcPath
         * @param defaultValue defaultValue
         * @param concat concat
         * @param concatSep concatSep
         * @param skipNone skipNone
         * @param castToStrForConcat castToStrForConcat
         */

        public VariableDef(
                String name,
                String srcPath,
                Object defaultValue,
                boolean concat,
                String concatSep,
                boolean skipNone,
                boolean castToStrForConcat) {
            this.name = Objects.requireNonNull(name, "name");
            this.srcPath = Objects.requireNonNull(srcPath, "srcPath");
            this.defaultValue = defaultValue;
            this.concat = concat;
            this.concatSep = concatSep == null ? "" : concatSep;
            this.skipNone = skipNone;
            this.castToStrForConcat = castToStrForConcat;
        }

        /**
         * fromDict.
         *
         * @param data data
         * @return variable def
         */

        public static VariableDef fromDict(Map<String, Object> data) {
            if (data == null) {
            throw new IllegalArgumentException("VariableDef must be a dict");
        }
            Object name = data.get("name");
            Object srcPath = data.get("src_path");
            if (!(name instanceof String) || ((String) name).isEmpty()) {
                throw new IllegalArgumentException("VariableDef.name must be a non-empty string");
            }
            if (!(srcPath instanceof String) || ((String) srcPath).isEmpty()) {
                throw new IllegalArgumentException("VariableDef.src_path must be a non-empty string");
            }
            Object concatSepObj = data.getOrDefault("concat_sep", "");
            if (concatSepObj == null) {
                concatSepObj = "";
            }
            String concatSep = concatSepObj instanceof String ? (String) concatSepObj : String.valueOf(concatSepObj);
            return new VariableDef(
                    (String) name,
                    (String) srcPath,
                    data.get("default"),
                    bool(data.get("concat"), false),
                    concatSep,
                    bool(data.get("skip_none"), true),
                    bool(data.get("cast_to_str_for_concat"), true));
        }

        /** @return name */
        public String name() {
            return name;
        }

        /** @return srcPath */
        public String srcPath() {
            return srcPath;
        }

        /** @return defaultValue */
        public Object defaultValue() {
            return defaultValue;
        }

        /** @return concat */
        public boolean concat() {
            return concat;
        }

        /** @return concatSep */
        public String concatSep() {
            return concatSep;
        }

        /** @return skipNone */
        public boolean skipNone() {
            return skipNone;
        }

        /** @return castToStrForConcat */
        public boolean castToStrForConcat() {
            return castToStrForConcat;
        }
    }
}
