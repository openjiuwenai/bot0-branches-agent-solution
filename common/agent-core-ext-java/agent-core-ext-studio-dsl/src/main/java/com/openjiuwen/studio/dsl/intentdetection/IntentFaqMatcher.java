/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.studio.dsl.adapter.external.PluginNodeHandler;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.kb.KnowledgeRequestContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FAQ knowledge match before LLM (Python {@code get_faq_result} / {@code get_search_answer}).
 *
 * @since 2026-08-26
 */

public final class IntentFaqMatcher {
    private static final int FEW_SHOT_NUM = 5;
    private static final int SEARCH_NUM = 1;
    private static final String DEFAULT_QUERY_CATE = "title";
    private static final String DEFAULT_CLASS_CATE = "content";

    /**
     * * * Python module constant {@code SEARCH_TYPE} (extension {@code intent_detection.py}).
     */
    private static final String SEARCH_TYPE = "faq";

    private IntentFaqMatcher() {}

    /**
     * FaqMatchResult.
     *
     * @param intentClass intentClass
     * @param fewShotExample fewShotExample
     * @param shortCircuit shortCircuit
     * @return result
     * @since 0.1.0
     */

    public record FaqMatchResult(String intentClass, String fewShotExample, boolean shortCircuit) {}

    /**
     * match.
     *
     * @param config config
     * @param tools tools
     * @param query query
     * @param chatHistory chatHistory
     * @param enableKnowledge enableKnowledge
     * @return result
     * @since 0.1.0
     */

    public static FaqMatchResult match(
            IntentDetectionConfig config,
            ToolRegistry tools,
            String query,
            List<Map<String, Object>> chatHistory,
            boolean enableKnowledge) {
        if (!enableKnowledge) {
        return new FaqMatchResult(config.defaultClass(), "", false);
    }
        String apiId = resolveApiId(config.kgConfig());
        if (apiId.isBlank() || tools == null) {
            return new FaqMatchResult(config.defaultClass(), "", false);
        }
        Optional<Tool> toolOpt = tools.find(apiId);
        if (toolOpt.isEmpty()) {
            return new FaqMatchResult(config.defaultClass(), "", false);
        }

        Map<String, Object> searchPayload = buildKgQuery(config, query);
        Map<String, Object> searchData = invokeSearch(toolOpt.get(), searchPayload, apiId);

        if ("faq".equalsIgnoreCase(config.kgScope())) {
            return matchFaqScope(config, searchData);
        }
        if ("doc_line".equals(SEARCH_TYPE)) {
            return new FaqMatchResult(config.defaultClass(), docSearch(config, searchData), false);
        }
        return new FaqMatchResult(config.defaultClass(), String.valueOf(searchData), false);
    }

    /**
     * Python {@code anayls_search}.
     *
     * @param config config
     * @param searchData searchData
     * @return result
     * @since 0.1.0
     */

    static List<Map<String, Object>> anaylsSearch(IntentDetectionConfig config, Map<String, Object> searchData) {
        List<Map<String, Object>> res = new ArrayList<>();
        try {
            Object docListRaw = searchData.get("doc_list");
            if (!(docListRaw instanceof List<?> docList)) {
                return res;
            }
            for (Object item : docList) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                double score = scoreOf(row.get("score"));
                if (score > config.recallThreshold() && res.size() < FEW_SHOT_NUM) {
                    Map<String, Object> add = new LinkedHashMap<>();
                    add.put("title", row.get(DEFAULT_QUERY_CATE));
                    add.put("content", row.get(DEFAULT_CLASS_CATE));
                    add.put("score", score);
                    res.add(add);
                }
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // Python soft-fails
        }
        return res;
    }

    /**
     * Python {@code doc_search}.
     *
     * @param config config
     * @param searchData searchData
     * @return result
     * @since 0.1.0
     */

    static String docSearch(IntentDetectionConfig config, Map<String, Object> searchData) {
        String res = "";
        try {
            Object listRaw = searchData.get("output_list");
            if (!(listRaw instanceof List<?> reslist)) {
                return res;
            }
            int num = 0;
            for (Object item : reslist) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                double score = scoreOf(row.get("score"));
                if (score > config.recallThreshold() && num < SEARCH_NUM) {
                    res += str(row.get("content")) + "\n";
                    num++;
                }
            }
        } catch (RuntimeException e) {
            return String.valueOf(searchData);
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeSearch(Tool tool, Map<String, Object> payload, String apiId) {
        try {
            Map<String, Object> kwargs = new LinkedHashMap<>();
            Map<String, String> headers = KnowledgeRequestContext.headers();
            if (!headers.isEmpty()) {
                kwargs.put("runtime_auth", Map.of("headers", headers));
            } else {
                kwargs.put("runtime_auth_headers", Map.of(apiId, headers, "default", headers));
            }
            Object raw = tool.invoke(payload, kwargs);
            Map<String, Object> formatted = PluginNodeHandler.formatApiOutputs(raw);
            if (formatted != null && !formatted.isEmpty()) {
                return formatted;
            }
            if (raw instanceof Map<?, ?> m) {
                Object err = m.get("errCode");
                if (err instanceof Number n && n.intValue() == 0) {
                    Object data = m.get("data");
                    if (data instanceof Map<?, ?> dm) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        dm.forEach((k, v) -> out.put(String.valueOf(k), v));
                        return out;
                    }
                }
            }
        } catch (Exception ignored) {
            // Python soft-fails search
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static FaqMatchResult matchFaqScope(IntentDetectionConfig config, Map<String, Object> searchData) {
        Object listRaw = searchData.get("output_list");
        if (!(listRaw instanceof List<?> outputList)) {
            return new FaqMatchResult(config.defaultClass(), "", false);
        }

        String intentClass = config.defaultClass();
        double qqThreshold = Double.NEGATIVE_INFINITY;
        for (Object item : outputList) {
            if (!(item instanceof Map<?, ?> temp)) {
                continue;
            }
            double score = scoreOf(temp.get("score"));
            if (score <= config.q2labelFewShotScore()) {
                continue;
            }
            String content = str(temp.get("content"));
            String tempCategory = config.defaultClass();
            for (int i = 0; i < config.categoryNameList().size(); i++) {
                if (content.equals(config.categoryNameList().get(i))) {
                    tempCategory = config.categoryList().get(i);
                    break;
                }
            }
            if (score > config.recallThreshold() && score > qqThreshold) {
                intentClass = tempCategory;
                qqThreshold = score;
            }
        }

        String fewShot = buildFewShotExamples(config, outputList);
        boolean shortCircuit = !intentClass.equals(config.defaultClass());
        return new FaqMatchResult(intentClass, fewShot, shortCircuit);
    }

    private static String buildFewShotExamples(IntentDetectionConfig config, List<?> outputList) {
        StringBuilder res = new StringBuilder("\n");
        int cnt = 0;
        for (Object item : outputList) {
            if (cnt >= FEW_SHOT_NUM) {
                break;
            }
            if (!(item instanceof Map<?, ?> temp)) {
                continue;
            }
            cnt++;
            String userInput = temp.containsKey("title")
                    ? str(temp.get("title"))
                    : str(temp.get("document_name"));
            int classIdx = intentIndexByName(config, str(temp.get("content")));
            if (classIdx < 0 || scoreOf(temp.get("score")) <= config.q2labelFewShotScore()) {
                continue;
            }
            res.append("样例")
                    .append(cnt)
                    .append(":\n用户输入: ")
                    .append(userInput)
                    .append("\n分类结果：")
                    .append(classIdx)
                    .append("\n");
        }
        return res.toString();
    }

    private static int intentIndexByName(IntentDetectionConfig config, String intentName) {
        for (int i = 0; i < config.categoryNameList().size(); i++) {
            if (intentName.equals(config.categoryNameList().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> buildKgQuery(IntentDetectionConfig config, String query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("query", query == null ? "" : query);
        request.put("scope", "faq".equalsIgnoreCase(config.kgScope()) ? "faq" : "doc");
        Object filter = config.kgConfig().get("filterString");
        if (filter == null) {
            filter = config.kgConfig().get("filter_string");
        }
        if (filter != null && !String.valueOf(filter).isEmpty()) {
            request.put("filter_string", String.valueOf(filter));
        }
        return request;
    }

    private static String resolveApiId(Map<String, Object> kg) {
        if (kg == null || kg.isEmpty()) {
        return "";
    }
        String apiId = str(kg.get("apiId"));
        if (!apiId.isBlank()) {
            return apiId;
        }
        return str(kg.get("id"));
    }

    private static double scoreOf(Object o) {
        if (o instanceof Number n) {
        return n.doubleValue();
    }
        if (o == null) {
            return 0;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
