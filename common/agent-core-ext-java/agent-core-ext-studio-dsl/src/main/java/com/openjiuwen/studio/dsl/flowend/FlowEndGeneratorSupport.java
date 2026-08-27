/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowend;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drain / dedupe Iterator values under {@code #end_*} keys (Python AsyncGenerator handling).
 *
 * @since 2026-08-26
 */

public final class FlowEndGeneratorSupport {
    private FlowEndGeneratorSupport() {}

    public static Map<String, String> buildOutputToInput(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) {
        return Map.of();
    }
        IdentityHashMap<Object, List<String>> genMap = new IdentityHashMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            if (isGenerator(e.getValue())) {
                genMap.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        Map<String, String> outToIn = new LinkedHashMap<>();
        for (List<String> keys : genMap.values()) {
            if (keys.size() <= 1) {
                continue;
            }
            String primary =
                    keys.stream()
                            .filter(k -> !k.startsWith(FlowEndEngine.OUTPUT_PREFIX))
                            .findFirst()
                            .orElse(keys.get(0));
            for (String k : keys) {
                if (k.startsWith(FlowEndEngine.OUTPUT_PREFIX)) {
                    outToIn.put(k, primary);
                }
            }
        }
        return outToIn;
    }

    /**
     * * Only {@code #end_*} generator keys are materialized (Python {@code process_generator_values_of_output}).
     *
     * @param inputs inputs
     * @param outToIn outToIn
     * @param streamCallback streamCallback
     * @return result
     * @since 0.1.0
     */
    public static Map<String, Object> processGeneratorValues(
            Map<String, Object> inputs, Map<String, String> outToIn, Consumer<Object> streamCallback) {
        if (inputs == null || inputs.isEmpty()) {
            return inputs;
        }
        Map<String, Object> work = new LinkedHashMap<>(inputs);
        IdentityHashMap<Object, String> flowRecord = new IdentityHashMap<>();
        String reasoning = "";
        for (Map.Entry<String, Object> e : List.copyOf(work.entrySet())) {
            String k = e.getKey();
            Object v = e.getValue();
            if ("reasoning_content".equals(k) && v instanceof String s) {
                reasoning = s;
            }
            if (!k.startsWith(FlowEndEngine.OUTPUT_PREFIX) || !isGenerator(v)) {
                continue;
            }
            if (outToIn != null && outToIn.containsKey(k)) {
                String src = outToIn.get(k);
                Object already = work.get(src);
                if (!isGenerator(already)) {
                    work.put(k, already);
                    continue;
                }
                if (flowRecord.containsKey(already)) {
                    work.put(k, work.get(flowRecord.get(already)));
                    continue;
                }
                String joined = drainToString(already, streamCallback);
                work.put(src, joined);
                work.put(k, joined);
                flowRecord.put(already, src);
                continue;
            }
            if (flowRecord.containsKey(v)) {
                work.put(k, work.get(flowRecord.get(v)));
                continue;
            }
            String joined = drainToString(v, streamCallback);
            work.put(k, joined);
            flowRecord.put(v, k);
        }
        if (!reasoning.isBlank()) {
            work.put("_reasoning_content", reasoning);
        }
        return work;
    }

    /**
     * isGenerator.
     *
     * @param v v
     * @return result
     * @since 0.1.0
     */

    public static boolean isGenerator(Object v) {
        return v instanceof Iterator || v instanceof Iterable;
    }

    /**
     * drainToString.
     *
     * @param v v
     * @param streamCallback streamCallback
     * @return result
     * @since 0.1.0
     */

    public static String drainToString(Object v, Consumer<Object> streamCallback) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it;
        if (v instanceof Iterator<?> iterator) {
            it = iterator;
        } else if (v instanceof Iterable<?> iterable) {
            it = iterable.iterator();
        } else {
            return v == null ? "" : String.valueOf(v);
        }
        while (it.hasNext()) {
            Object data = it.next();
            if (streamCallback != null) {
                streamCallback.accept(data);
            }
            if (data != null) {
                sb.append(data);
            }
        }
        return sb.toString();
    }

    public static Iterator<Map<String, Object>> wrapIteratorChunks(Iterator<?> gen, String varName, int fillCount) {
        List<Map<String, Object>> frames = new ArrayList<>();
        boolean has = false;
        if (gen != null) {
            while (gen.hasNext()) {
                has = true;
                frames.add(Map.of(varName, gen.next()));
            }
        }
        if (!has && fillCount > 0) {
            for (int i = 0; i < fillCount; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put(varName, null);
                frames.add(m);
            }
        }
        return frames.iterator();
    }
}
