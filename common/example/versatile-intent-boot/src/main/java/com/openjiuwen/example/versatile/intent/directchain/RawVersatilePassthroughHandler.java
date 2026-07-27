package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * 业务终端 handler：调用自身 versatile mock（url-template），把业务返回的每条 SSE
 * 事件 JSON 原样解析成 Map，以 TYPE_CHUNK 透传给 observer——不做意图识别、不产 a2a_delegate、
 * 不折叠成答案。用于直链场景下让业务的原始结构化 SSE 经 /v1/query 流式端点到达 client。
 */
public class RawVersatilePassthroughHandler implements AgentHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VersatileProperties properties;
    private final DirectChainSseClient client;

    public RawVersatilePassthroughHandler(VersatileProperties properties) {
        this(properties, new DirectChainSseClient());
    }

    RawVersatilePassthroughHandler(VersatileProperties properties, DirectChainSseClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            String url = resolveUrl(request);
            client.postStream(url, versatileBody(request), Map.of(), properties.getTimeout(), line -> {
                if (observer.isCancelled()) {
                    throw new CancellationException();
                }
                parseLine(line).ifPresent(p -> observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, p)));
            });
            observer.onComplete();
        } catch (CancellationException ignored) {
            observer.onComplete();
        } catch (Exception e) {
            observer.onError(e);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        List<Object> collected = new ArrayList<>();
        try {
            client.postStream(resolveUrl(request), versatileBody(request), Map.of(),
                    properties.getTimeout(), line -> parseLine(line).ifPresent(collected::add));
        } catch (Exception e) {
            throw new IllegalStateException("Raw passthrough failed", e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", collected.toString());
        return new QueryResponse(result, request.getConversationId());
    }

    private String resolveUrl(ServeRequest request) {
        String cid = request.getConversationId() != null ? request.getConversationId() : "";
        return properties.getUrlTemplate().replace("{conversation_id}", cid);
    }

    private Map<String, Object> versatileBody(ServeRequest request) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", request.lastUserQuery());
        inputs.put("messages", request.getMessages());
        return Map.of("inputs", inputs);
    }

    private Optional<Object> parseLine(String line) {
        String payload = line.startsWith("data:") ? line.substring(5).strip() : line.strip();
        if (payload.isEmpty() || payload.charAt(0) != '{') {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(payload, Object.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}