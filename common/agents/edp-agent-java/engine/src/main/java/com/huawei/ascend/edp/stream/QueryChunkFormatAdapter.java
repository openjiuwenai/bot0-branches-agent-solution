/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.stream;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * QueryChunk 流式输出适配器，将 适配版 AgentCore 的结构化 chunk 格式
 * 过滤并转换为前端可正常显示的纯文本 chunk。
 *
 * <h3>适配版 QueryChunk 格式问题</h3>
 * <p>适配版 {@code JiuwenCoreAgentExtHandler} 输出的 QueryChunk.data 包含四种结构化类型：</p>
 * <ul>
 *     <li>{@code llm_reasoning} — LLM 内部推理过程（逐token），不应显示给用户</li>
 *     <li>{@code llm_output} — LLM 输出文本（逐token），应显示给用户</li>
 *     <li>{@code llm_usage} — token用量统计，不应显示给用户</li>
 *     <li>{@code answer} — 最终完整答案，应显示给用户</li>
 * </ul>
 *
 * <h3>适配策略</h3>
 * <ol>
 *     <li>丢弃 {@code llm_reasoning} chunk — 消除推理过程噪音</li>
 *     <li>丢弃 {@code llm_usage} chunk — 消除token统计噪音 + 减少帧数量约60%</li>
 *     <li>转换 {@code llm_output} chunk → 纯文本 chunk — 前端可直接显示</li>
 *     <li>转换 {@code answer} chunk → 纯文本 chunk — 前端可显示最终答案</li>
 *     <li>保留 {@code passthrough_node} chunk — Versatile进度事件不变</li>
 *     <li>保留 {@code interrupt/error} chunk — 中断/错误信号不变</li>
 * </ol>
 *
 * @since 2024-01-01
 */
public class QueryChunkFormatAdapter implements QueryStreamObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryChunkFormatAdapter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String TYPE_LLM_REASONING = "llm_reasoning";
    private static final String TYPE_LLM_USAGE = "llm_usage";
    private static final String TYPE_LLM_OUTPUT = "llm_output";
    private static final String TYPE_ANSWER = "answer";
    private static final String TYPE_PASSTHROUGH_NODE = "passthrough_node";

    private final QueryStreamObserver delegate;
    private final String conversationId;

    private int discardedFrames = 0;
    private int transformedFrames = 0;
    private int forwardedFrames = 0;

    public QueryChunkFormatAdapter(QueryStreamObserver delegate, String conversationId) {
        this.delegate = delegate;
        this.conversationId = conversationId;
    }

    @Override
    /** On next. */
    public void onNext(QueryChunk chunk) {
        if (chunk == null || chunk.getData() == null) {
            discardedFrames++;
            return;
        }

        Object data = chunk.getData();

        if (data instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String chunkType = String.valueOf(map.getOrDefault("type", ""));

            switch (chunkType) {
                case TYPE_LLM_REASONING :
                    discardedFrames++;
                    return;
                case TYPE_LLM_USAGE :
                    discardedFrames++;
                    return;
                case TYPE_LLM_OUTPUT :
                    transformLlmOutputToPlainText(chunk, map);
                    return;
                case TYPE_ANSWER :
                    transformAnswerToPlainText(chunk, map);
                    return;
                case TYPE_PASSTHROUGH_NODE :
                    forwardedFrames++;
                    delegate.onNext(chunk);
                    return;
                default :
                    QueryChunk adapted = adaptPassthrough(chunk, map);
                    if (adapted != null) {
                        delegate.onNext(adapted);
                        return;
                    }
                    forwardedFrames++;
                    delegate.onNext(chunk);
                    return;
            }
        }

        if (data instanceof String text) {
            if (text.trim().startsWith("{")) {
                Map<String, Object> parsed = parseJsonToMap(text);
                if (parsed != null && parsed.containsKey("type")) {
                    String chunkType = String.valueOf(parsed.get("type"));

                    switch (chunkType) {
                        case TYPE_LLM_REASONING :
                            discardedFrames++;
                            return;
                        case TYPE_LLM_USAGE :
                            discardedFrames++;
                            return;
                        case TYPE_LLM_OUTPUT :
                            transformLlmOutputToPlainText(chunk, parsed);
                            return;
                        case TYPE_ANSWER :
                            transformAnswerToPlainText(chunk, parsed);
                            return;
                        default :
                            QueryChunk adaptedStr = adaptPassthrough(chunk, parsed);
                            if (adaptedStr != null) {
                                delegate.onNext(adaptedStr);
                                return;
                            }
                            forwardedFrames++;
                            delegate.onNext(chunk);
                            return;
                    }
                }
            }
            forwardedFrames++;
            delegate.onNext(chunk);
            return;
        }

        forwardedFrames++;
        delegate.onNext(chunk);
    }

    @Override
    /** On error. */
    public void onError(Throwable error) {
        logStats();
        delegate.onError(error);
    }

    @Override
    /** On complete. */
    public void onComplete() {
        logStats();
        delegate.onComplete();
    }

    @Override
    /** Checks whether cancelled. */
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    private void transformLlmOutputToPlainText(QueryChunk originalChunk, Map<String, Object> map) {
        Object payload = map.get("payload");
        String content = extractPayloadContent(payload);

        if (content == null || content.isEmpty()) {
            discardedFrames++;
            return;
        }

        transformedFrames++;
        delegate.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, content));
    }

    private void transformAnswerToPlainText(QueryChunk originalChunk, Map<String, Object> map) {
        Object payload = map.get("payload");
        String output = extractPayloadOutput(payload);

        if (output == null || output.isEmpty()) {
            discardedFrames++;
            return;
        }

        transformedFrames++;
        LOGGER.info("QueryChunkFormatAdapter: answer chunk transformed, length={}", output.length());
        delegate.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, output));
    }

    private String extractPayloadContent(Object payload) {
        if (payload instanceof Map<?, ?> payloadMap) {
            Object content = payloadMap.get("content");
            return content != null ? String.valueOf(content) : null;
        }
        return null;
    }

    private String extractPayloadOutput(Object payload) {
        if (payload instanceof Map<?, ?> payloadMap) {
            Object output = payloadMap.get("output");
            return output != null ? String.valueOf(output) : null;
        }
        return null;
    }

    private QueryChunk adaptPassthrough(QueryChunk chunk, Map<String, Object> map) {
        String chunkType = String.valueOf(map.getOrDefault("type", ""));

        if (map.containsKey("menu_type") || (map.containsKey("data") && hasMenuType(map.get("data")))) {
            LOGGER.debug("QueryChunkFormatAdapter: detected menu_type, marking as PASSTHROUGH_NODE");
            transformedFrames++;
            return new QueryChunk(TYPE_PASSTHROUGH_NODE, chunk.getData());
        }

        if ("answer".equals(chunkType) && map.containsKey("output")) {
            String output = String.valueOf(map.get("output"));
            if (output != null && !output.isEmpty()) {
                transformedFrames++;
                LOGGER.debug("QueryChunkFormatAdapter: detected answer envelope, extracting output");
                return new QueryChunk(QueryChunk.TYPE_CHUNK, output);
            }
        }

        if (map.containsKey("node_type") || (map.containsKey("data") && hasNodeType(map.get("data")))) {
            LOGGER.debug("QueryChunkFormatAdapter: detected node_type, marking as PASSTHROUGH_NODE");
            transformedFrames++;
            return new QueryChunk(TYPE_PASSTHROUGH_NODE, chunk.getData());
        }

        return null;
    }

    private boolean hasMenuType(Object dataObj) {
        if (dataObj instanceof Map<?, ?> map) {
            return map.containsKey("menu_type");
        }
        if (dataObj instanceof String str) {
            Map<String, Object> parsed = parseJsonToMap(str);
            return parsed != null && parsed.containsKey("menu_type");
        }
        return false;
    }

    private boolean hasNodeType(Object dataObj) {
        if (dataObj instanceof Map<?, ?> map) {
            return map.containsKey("node_type");
        }
        if (dataObj instanceof String str) {
            Map<String, Object> parsed = parseJsonToMap(str);
            return parsed != null && parsed.containsKey("node_type");
        }
        return false;
    }

    private Map<String, Object> parseJsonToMap(String json) {
        try {
            return OBJECT_MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                    });
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }

    private void logStats() {
        if (discardedFrames > 0 || transformedFrames > 0) {
            LOGGER.info("QueryChunkFormatAdapter stats for conv={}: discarded={}, transformed={}, forwarded={}",
                    conversationId, discardedFrames, transformedFrames, forwardedFrames);
        }
    }
}