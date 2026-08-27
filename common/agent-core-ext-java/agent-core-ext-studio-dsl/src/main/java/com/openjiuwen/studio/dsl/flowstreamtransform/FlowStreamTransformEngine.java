/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowstreamtransform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.util.DictStreamTransformConfig;
import com.openjiuwen.studio.dsl.util.DictStreamTransformer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict 1:1 of Python {@code flow_stream_transform.FlowStreamTransform}.
 *
 * <p>Async Python streams map to JVM {@link Iterable}/{@link Iterator}. {@link #collect} /
 * {@link #transform} call {@link #resolveStreamInputs} then invoke / yield frames like Python.
 *
 * @since 2026-08-26
 */
public final class FlowStreamTransformEngine {
    /** Python USER_FIELDS. */
    public static final String USER_FIELDS = "userFields";
    /** Python STREAM_TYPE_PARTIAL_CONTENT. */
    public static final String STREAM_TYPE_PARTIAL_CONTENT = "end node stream";
    /** Python STREAM_TYPE_MESSAGE_END. */
    public static final String STREAM_TYPE_MESSAGE_END = "message_end";

    /** Python CONFIG_ERROR. */
    public static final int CONFIG_ERROR = 101170;
    /** Python INPUT_INVALID. */
    public static final int INPUT_INVALID = 101171;
    /** Python TRANSFORMER_CONFIG_ERROR. */
    public static final int TRANSFORMER_CONFIG_ERROR = 101172;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern PY_BOOL_NONE =
            Pattern.compile("\\b(True|False|None)\\b");

    private final String nodeId;
    private final Map<String, Object> transformerConf;
    private final boolean parseJsonStrings;
    private final String sourceField;
    private final String outputField;
    private final boolean directAssignOutput;
    private final StreamMetadata metadata;

    /**
     * Build engine from node configs (Python {@code FlowStreamTransform.__init__}).
     *
     * @param node assembled node
     */
    @SuppressWarnings("unchecked")
    public FlowStreamTransformEngine(AssembledNode node) {
        this.nodeId = node.id();
        Map<String, Object> conf = node.configs() == null ? Map.of() : node.configs();

        Object transformerRaw = conf.get("transformer");
        if (transformerRaw == null) {
            this.transformerConf = Map.of();
        } else if (transformerRaw instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            this.transformerConf = copy;
        } else {
            throw buildError(
                    nodeId,
                    CONFIG_ERROR,
                    "flow_stream_transform config error, reason: 'transformer' must be a dict");
        }

        Object parseFlag;
        if (conf.containsKey("parse_json_strings")) {
            parseFlag = conf.get("parse_json_strings");
        } else if (conf.containsKey("parseJsonStrings")) {
            parseFlag = conf.get("parseJsonStrings");
        } else {
            parseFlag = Boolean.TRUE;
        }
        this.parseJsonStrings =
                parseFlag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(parseFlag));

        String src = "_input";
        String out = "";
        boolean direct = true;
        Object ufCfg = conf.get("userFields");
        if (ufCfg instanceof Map<?, ?> ufMap) {
            Object inputs = ufMap.get("inputs");
            if (inputs instanceof List<?> list && list.size() == 1) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> firstMap && firstMap.containsKey("id")) {
                    src = String.valueOf(firstMap.get("id"));
                }
            }
            Object outputs = ufMap.get("outputs");
            if (outputs instanceof List<?> list && list.size() == 1) {
                Object first = list.get(0);
                if (first instanceof Map<?, ?> firstMap && firstMap.containsKey("id")) {
                    out = String.valueOf(firstMap.get("id"));
                    direct = false;
                }
            }
        }
        this.sourceField = src;
        this.outputField = out;
        this.directAssignOutput = direct;
        this.metadata = StreamMetadata.fromNode(node);
    }

    public String sourceField() {
        return sourceField;
    }

    public String outputField() {
        return outputField;
    }

    public boolean directAssignOutput() {
        return directAssignOutput;
    }

    public boolean parseJsonStrings() {
        return parseJsonStrings;
    }

    public Map<String, Object> transformerConf() {
        return transformerConf;
    }

    public StreamMetadata metadata() {
        return metadata;
    }

    /**
     * Python {@code invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context (unused; parity signature)
     * @return result map with userFields
     */
    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> uf = userFieldsOf(inputs);
        Object originStream = uf.get(sourceField);

        if (originStream == null) {
            return buildResult(null);
        }
        if (!isIterableStream(originStream)) {
            throw buildError(
                    nodeId,
                    INPUT_INVALID,
                    "flow_stream_transform input invalid, reason: '"
                            + sourceField
                            + "' must be an async iterable");
        }

        DictStreamTransformConfig cfg = parseTransformerConfig();
        List<Map<String, Object>> dictFrames = iterDictFrames(toIterator(originStream));
        List<Map<String, Object>> outFrames = new DictStreamTransformer(cfg).transform(dictFrames);
        Map<String, Object> last = handleStreaming(outFrames, session);
        return buildResult(last);
    }

    /**
     * Python {@code collect} — resolve stream inputs then {@link #invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return invoke result
     */
    public Map<String, Object> collect(Object inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> resolved = resolveStreamInputs(inputs);
        return invoke(resolved, session, context);
    }

    /**
     * Python {@code transform} — resolve, then yield OutputSchema frames (no session write).
     *
     * @param inputs inputs
     * @param session session (unused for writes; parity signature)
     * @param context context
     * @return OutputSchema iterator
     */
    public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> resolved = resolveStreamInputs(inputs);
        Map<String, Object> uf = userFieldsOf(resolved);
        Object originStream = uf.get(sourceField);
        if (originStream == null) {
            return Collections.emptyIterator();
        }
        if (!isIterableStream(originStream)) {
            throw buildError(
                    nodeId,
                    INPUT_INVALID,
                    "flow_stream_transform input invalid, reason: '"
                            + sourceField
                            + "' must be an async iterable");
        }

        DictStreamTransformConfig cfg = parseTransformerConfig();
        List<Map<String, Object>> dictFrames = iterDictFrames(toIterator(originStream));
        List<Map<String, Object>> outFrames = new DictStreamTransformer(cfg).transform(dictFrames);

        List<Object> schemas = new ArrayList<>();
        int idx = 0;
        Map<String, Object> prev = null;
        for (Map<String, Object> frame : outFrames) {
            if (prev != null) {
                schemas.add(new OutputSchema(
                        STREAM_TYPE_PARTIAL_CONTENT, idx, getDataOfStreamingWithMetadata(prev, metadata, null)));
                idx++;
            }
            prev = frame;
        }
        if (prev != null) {
            schemas.add(new OutputSchema(
                    STREAM_TYPE_MESSAGE_END, idx, getDataOfStreamingWithMetadata(prev, metadata, null)));
        }
        return schemas.iterator();
    }

    /**
     * Python {@code _resolve_stream_inputs}.
     *
     * <p>Top-level Iterable values (AsyncGenerator analogue) are joined to a string; nested streams
     * under {@code userFields} are left intact for invoke/transform.
     *
     * @param inputs raw inputs
     * @return resolved map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveStreamInputs(Object inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        if (!(inputs instanceof Map<?, ?>)) {
            if (isIterableStream(inputs)) {
                List<Map<String, Object>> collected = new ArrayList<>();
                Iterator<?> it = toIterator(inputs);
                while (it.hasNext()) {
                    Object chunk = it.next();
                    if (chunk instanceof Map<?, ?> m) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        collected.add(copy);
                    }
                }
                if (collected.isEmpty()) {
                    return new LinkedHashMap<>();
                }
                return collected.get(collected.size() - 1);
            }
            return new LinkedHashMap<>();
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) inputs).entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (isAsyncGeneratorAnalogue(value)) {
                StringBuilder chunks = new StringBuilder();
                Iterator<?> it = toIterator(value);
                while (it.hasNext()) {
                    Object frame = it.next();
                    if (frame instanceof String s) {
                        chunks.append(s);
                    } else if (frame instanceof Map<?, ?> fm) {
                        Object resp = fm.containsKey("response") ? fm.get("response") : fm.get("answer");
                        if (resp != null && !String.valueOf(resp).isEmpty()) {
                            chunks.append(String.valueOf(resp));
                        }
                    } else if (frame != null) {
                        chunks.append(String.valueOf(frame));
                    }
                }
                resolved.put(key, chunks.toString());
            } else {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    /**
     * Python {@code get_data_of_streaming_with_metadata}.
     *
     * @param answer answer
     * @param metadata metadata
     * @param outputs optional user-field outputs
     * @return payload
     */
    public static Map<String, Object> getDataOfStreamingWithMetadata(
            Object answer, StreamMetadata metadata, Map<String, Object> outputs) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("answer", answer);
        base.put("node_id", metadata.nodeId());
        base.put("node_name", metadata.nodeName());
        base.put("node_type", metadata.nodeType());
        base.put("should_interrupt", metadata.shouldInterrupt());
        if (outputs != null && !outputs.isEmpty()) {
            base.put("outputs", Map.of(USER_FIELDS, outputs));
        }
        return base;
    }

    /**
     * Build NodeExecutionException mirroring Python FlowStreamTransform errors.
     *
     * @param nodeId nodeId
     * @param errorCode python error code
     * @param message message
     * @return exception
     */
    public static NodeExecutionException buildError(String nodeId, int errorCode, String message) {
        NodeCauseCode cause =
                errorCode == INPUT_INVALID ? NodeCauseCode.NODE_INVOKE_FAILED : NodeCauseCode.NODE_CONFIG_INVALID;
        return new NodeExecutionException(
                nodeId, "jiuwen.streamTransform", cause, message + " [pythonErrorCode=" + errorCode + "]");
    }

    private DictStreamTransformConfig parseTransformerConfig() {
        try {
            return DictStreamTransformConfig.fromDict(transformerConf);
        } catch (RuntimeException e) {
            throw buildError(
                    nodeId,
                    TRANSFORMER_CONFIG_ERROR,
                    "flow_stream_transform transformer config error, reason: " + e.getMessage());
        }
    }

    private Map<String, Object> buildResult(Map<String, Object> value) {
        Map<String, Object> wrap = new LinkedHashMap<>();
        if (directAssignOutput) {
            wrap.put(USER_FIELDS, value);
        } else {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put(outputField, value);
            wrap.put(USER_FIELDS, nested);
        }
        return wrap;
    }

    private Map<String, Object> handleStreaming(List<Map<String, Object>> outStream, NodeSessionApi session) {
        int idx = 0;
        Map<String, Object> prev = null;
        for (Map<String, Object> frame : outStream) {
            if (prev != null) {
                writeFrame(session, STREAM_TYPE_PARTIAL_CONTENT, idx, prev);
                idx++;
            }
            prev = frame;
        }
        if (prev != null) {
            writeFrame(session, STREAM_TYPE_MESSAGE_END, idx, prev);
        }
        return prev;
    }

    private void writeFrame(NodeSessionApi session, String type, int index, Map<String, Object> answer) {
        if (session == null) {
            return;
        }
        OutputSchema schema =
                new OutputSchema(type, index, getDataOfStreamingWithMetadata(answer, metadata, null));
        try {
            session.writeStream(schema);
        } catch (RuntimeException ignored) {
            // mock session without stream writer
        }
    }

    List<Map<String, Object>> iterDictFrames(Iterator<?> origin) {
        List<Map<String, Object>> frames = new ArrayList<>();
        while (origin.hasNext()) {
            Object item = origin.next();
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> asMap = new LinkedHashMap<>();
                map.forEach((k, v) -> asMap.put(String.valueOf(k), v));
                frames.add(asMap);
                continue;
            }

            Object dataAttr = tryGetDataAttribute(item);
            if (dataAttr instanceof Map<?, ?> dataMap) {
                Object ans = dataMap.get("answer");
                if (ans instanceof Map<?, ?> ansMap) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    ansMap.forEach((k, v) -> out.put(String.valueOf(k), v));
                    frames.add(out);
                    continue;
                }
                if (ans instanceof String || ans instanceof byte[]) {
                    item = ans;
                }
            }

            if (!parseJsonStrings) {
                continue;
            }

            if (item instanceof byte[] bytes) {
                try {
                    item = new String(bytes, StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    continue;
                }
            }

            if (item instanceof String str) {
                String s = str.strip();
                if (s.startsWith("data:")) {
                    s = s.substring(5).strip();
                }
                Map<String, Object> parsed = parseLiteralOrJson(s);
                if (parsed != null) {
                    frames.add(parsed);
                }
            }
        }
        return frames;
    }

    /**
     * Python {@code ast.literal_eval} first, then JSON (Studio tests may use JSON literals).
     */
    static Map<String, Object> parseLiteralOrJson(String s) {
        Map<String, Object> literal = tryPythonLiteralMap(s);
        if (literal != null) {
            return literal;
        }
        try {
            return MAPPER.readValue(s, MAP_TYPE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> tryPythonLiteralMap(String s) {
        if (s == null || s.isBlank() || !s.strip().startsWith("{")) {
            return null;
        }
        try {
            String normalized = PY_BOOL_NONE.matcher(s).replaceAll(m -> {
                return switch (m.group(1)) {
                    case "True" -> "true";
                    case "False" -> "false";
                    default -> "null";
                };
            });
            if (!normalized.contains("\"")) {
                normalized = normalized.replace('\'', '"');
            }
            return MAPPER.readValue(normalized, MAP_TYPE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object tryGetDataAttribute(Object item) {
        if (item == null) {
            return null;
        }
        try {
            var m = item.getClass().getMethod("getData");
            return m.invoke(item);
        } catch (ReflectiveOperationException ignored) {
            try {
                var f = item.getClass().getField("data");
                return f.get(item);
            } catch (ReflectiveOperationException ignored2) {
                return null;
            }
        }
    }

    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    /**
     * Top-level AsyncGenerator analogue: Iterable/Iterator/array that is not CharSequence and not Map.
     */
    private static boolean isAsyncGeneratorAnalogue(Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof CharSequence) {
            return false;
        }
        return isIterableStream(value);
    }

    static boolean isIterableStream(Object originStream) {
        if (originStream instanceof Iterator<?>
                || originStream instanceof Iterable<?>
                || originStream instanceof Object[]) {
            return !(originStream instanceof CharSequence);
        }
        return false;
    }

    static Iterator<?> toIterator(Object originStream) {
        if (originStream instanceof Iterator<?> it) {
            return it;
        }
        if (originStream instanceof Object[] arr) {
            return java.util.Arrays.asList(arr).iterator();
        }
        if (originStream instanceof Iterable<?> it) {
            return it.iterator();
        }
        throw new IllegalArgumentException("not iterable");
    }

    /**
     * Node stream metadata (Python {@code WorkflowMetadata}).
     *
     * @param nodeId nodeId
     * @param nodeType nodeType
     * @param nodeName nodeName
     * @param shouldInterrupt shouldInterrupt
     */
    public record StreamMetadata(String nodeId, String nodeType, String nodeName, boolean shouldInterrupt) {
        /**
         * fromNode.
         *
         * @param node node
         * @return metadata
         */
        @SuppressWarnings("unchecked")
        public static StreamMetadata fromNode(AssembledNode node) {
            Map<String, Object> configs = node.configs();
            Object metaObj = configs.get("metadata");
            Map<String, Object> meta = Map.of();
            if (metaObj instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                meta = copy;
            }
            String nodeId = firstNonBlank(meta.get("node_id"), configs.get("node_id"), node.id());
            String nodeType = firstNonBlank(
                    meta.get("node_type"),
                    configs.get("node_type"),
                    configs.get("nodeType"),
                    "StreamTransform");
            String nodeName = firstNonBlank(
                    meta.get("node_name"),
                    configs.get("node_name"),
                    configs.get("nodeName"),
                    configs.get("name"),
                    node.id());
            boolean shouldInterrupt = bool(
                    meta.getOrDefault(
                            "should_interrupt",
                            configs.getOrDefault("should_interrupt", configs.get("shouldInterrupt"))),
                    false);
            return new StreamMetadata(nodeId, nodeType, nodeName, shouldInterrupt);
        }

        private static String firstNonBlank(Object... vals) {
            for (Object v : vals) {
                if (v != null && !String.valueOf(v).isBlank()) {
                    return String.valueOf(v);
                }
            }
            return "";
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
    }
}
