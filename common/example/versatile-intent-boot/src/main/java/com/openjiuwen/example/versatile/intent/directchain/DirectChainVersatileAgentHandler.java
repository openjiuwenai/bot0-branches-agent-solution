package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * 包装 {@link VersatileAgentHandler}：复用其意图识别 + SSE 解析 + a2a_delegate 生产，
 * 但在流式路径截胡 a2a_delegate 中断——默认（{@code shouldDirectChain} true）不把中断交给
 * orchestrator（那会经 a2a 折叠成 String），而是改为调 gateway URL（复用
 * {@link A2AGatewayCardResolver#resolveJsonRpcUrl}，带 X-Direct-Chain:true），由 gateway
 * 隧道转发到目标 /v1/query stream:true，把回传的 data: 行解析成 Map 以 TYPE_CHUNK 透传给 client。
 * 仅当 agentCard 在 {@code a2aForwardAgentCards} 例外集时，原样转发 a2a_delegate（走 a2a）。
 *
 * <p>非流式 query() 直接委托 delegate（走 a2a_delegate，后续再支持非流直链）。
 */
public class DirectChainVersatileAgentHandler implements AgentHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIRECT_CHAIN_HEADER = "X-Direct-Chain";

    private final VersatileAgentHandler delegate;
    private final DirectChainProperties props;
    private final A2AGatewayCardResolver gatewayResolver;
    private final DirectChainSseClient client;

    public DirectChainVersatileAgentHandler(VersatileProperties versatileProps, DirectChainProperties props,
            A2AGatewayCardResolver gatewayResolver) {
        this(versatileProps, props, gatewayResolver, new DirectChainSseClient());
    }

    DirectChainVersatileAgentHandler(VersatileProperties versatileProps, DirectChainProperties props,
            A2AGatewayCardResolver gatewayResolver, DirectChainSseClient client) {
        this.delegate = new VersatileAgentHandler(versatileProps);
        this.props = props;
        this.gatewayResolver = gatewayResolver;
        this.client = client;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        return delegate.query(request);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        delegate.streamQuery(request, new QueryStreamObserver() {
            private boolean directChained = false;
            private boolean terminated = false;

            @Override
            public void onNext(QueryChunk chunk) {
                if (directChained) {
                    return;   // suppress everything after direct-chain takeover
                }
                if (isA2aDelegate(chunk)) {
                    String agentName = agentNameOf(chunk);
                    if (props.shouldDirectChain(agentName)) {
                        directChained = true;
                        doDirectChain(agentName, request, observer, this);
                        return;
                    }
                }
                observer.onNext(chunk);
            }

            @Override
            public void onError(Throwable error) {
                if (terminated) {
                    return;
                }
                terminated = true;
                observer.onError(error);
            }

            @Override
            public void onComplete() {
                if (terminated) {
                    return;
                }
                terminated = true;
                observer.onComplete();
            }

            @Override
            public boolean isCancelled() { return observer.isCancelled(); }
        });
    }

    private void doDirectChain(String agentCard, ServeRequest request, QueryStreamObserver observer,
            QueryStreamObserver wrapper) {
        try {
            String url = gatewayResolver.resolveJsonRpcUrl(agentCard);
            client.postStream(url, queryBody(request), Map.of(DIRECT_CHAIN_HEADER, "true"),
                    props.getTimeout(), line -> {
                        if (wrapper.isCancelled()) {
                            throw new CancellationException();
                        }
                        // Streamed direct-chain chunks bypass the wrapper's directChained
                        // guard (which would suppress them) and go straight to the outer
                        // observer; the wrapper is only used for terminal-state routing.
                        parseLine(line).ifPresent(p -> observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, p)));
                    });
        } catch (CancellationException ignored) {
            // 下游会 onComplete
        } catch (Exception e) {
            wrapper.onError(e);
        }
    }

    private static boolean isA2aDelegate(QueryChunk chunk) {
        if (!QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
            return false;
        }
        if (!(chunk.getData() instanceof Map<?, ?> map)) {
            return false;
        }
        if (!(map.get("context") instanceof Map<?, ?> ctx)) {
            return false;
        }
        return "a2a_delegate".equals(String.valueOf(ctx.get("_interrupt_kind")));
    }

    private static String agentNameOf(QueryChunk chunk) {
        if (chunk.getData() instanceof Map<?, ?> map) {
            Object n = map.get("agentName");
            return n != null ? String.valueOf(n) : "";
        }
        return "";
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

    private Map<String, Object> queryBody(ServeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", request.getConversationId() != null ? request.getConversationId() : "");
        body.put("stream", true);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        msg.put("content", request.lastUserQuery());
        body.put("messages", List.of(msg));
        return body;
    }
}
