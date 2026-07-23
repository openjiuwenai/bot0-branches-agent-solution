/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentAnswerExtractor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.app.controller.a2a.client.RemoteInputRequiredException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A2A Gateway {@link RemoteAgentCaller} implementation: routes via the A2A SDK
 * {@link Client} to {@code gatewayBaseUrl + "/a2a/" + agentId} (resolved by
 * {@link A2AGatewayCardResolver}) and consumes
 * {@link RemoteAgentCall#responseContent()} to append an assistant message to
 * the forwarded {@link ServeRequest#messages}.
 *
 * <p>Per the gateway's integration requirements, every request carries:
 * <ul>
 *   <li>{@code token} header — service credential from
 *       {@link A2AGatewayProperties#getToken()} (required; call fails fast if absent)</li>
 *   <li>{@code userId} header — propagated from
 *       {@link ServeRequest#getUserId()}</li>
 *   <li>{@code versionNode} header — optional, from
 *       {@link A2AGatewayProperties#getVersionNode()}</li>
 *   <li>{@code X-B3-TraceId} — propagated verbatim from the upstream HTTP
 *       request header of the same name (when present)</li>
 *   <li>{@code X-B3-SpanId} — freshly generated per call via
 *       {@link UUID#randomUUID()} (with dashes stripped), so each cross-layer
 *       hop has its own span id</li>
 *   <li>{@code X-B3-ParentSpanId} — set to the upstream request's
 *       {@code X-B3-SpanId} (when present), linking this hop under the caller's
 *       span</li>
 *   <li>{@code X-B3-Sampled} — propagated verbatim from the upstream HTTP
 *       request header of the same name (when present; expected value
 *       {@code 0} or {@code 1})</li>
 *   <li>{@code X-Biz-Tag} — propagated verbatim from the upstream HTTP
 *       request header of the same name (when present)</li>
 * </ul>
 *
 * <p>Headers are injected per-call via {@link ClientCallContext}. The SDK's
 * {@code JSONRPCTransport.createPostBuilder} iterates
 * {@link org.a2aproject.sdk.client.transport.spi.interceptors.PayloadAndHeaders#getHeaders()}
 * (populated from {@link ClientCallContext#getHeaders()}) and adds each entry
 * to the HTTP request, so all headers — static (token, versionNode) and
 * per-call (userId, X-B3-*) — flow through the same channel.
 *
 * <p>The ephemeral {@link AgentCard} built per agentId uses the uppercase
 * {@link #PROTOCOL_BINDING_JSONRPC} constant for {@code protocolBinding}. The
 * SDK's {@code TransportProtocol.JSONRPC.asString()} returns {@code "JSONRPC"};
 * a lowercase value silently breaks {@code ClientBuilder.findBestClientTransport}
 * with "No compatible transport found" — this was the root cause of the
 * earlier misdiagnosis as a ServiceLoader issue.
 *
 * <p>Activated by {@code openjiuwen.service.a2a-gateway.enabled=true}.
 *
 * @since 0.1.0
 */
public class A2AGatewayRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(A2AGatewayRemoteAgentCaller.class);

    /**
     * Uppercase A2A protocol binding matching
     * {@code TransportProtocol.JSONRPC.asString()}. The SDK matches
     * {@code AgentInterface.protocolBinding()} against this value case-sensitively
     * in {@code ClientBuilder.findBestClientTransport}.
     */
    static final String PROTOCOL_BINDING_JSONRPC = "JSONRPC";

    private static final String HEADER_TOKEN = "token";
    private static final String HEADER_USER_ID = "userId";
    private static final String HEADER_VERSION_NODE = "versionNode";
    private static final String HEADER_B3_TRACE_ID = "X-B3-TraceId";
    private static final String HEADER_B3_SPAN_ID = "X-B3-SpanId";
    private static final String HEADER_B3_PARENT_SPAN_ID = "X-B3-ParentSpanId";
    private static final String HEADER_B3_SAMPLED = "X-B3-Sampled";
    private static final String HEADER_BIZ_TAG = "X-Biz-Tag";

    private final A2AGatewayProperties properties;
    private final RemoteAgentCardResolver cardResolver;
    private final A2AHttpClient httpClient;
    private final Map<String, Client> clientCache = new ConcurrentHashMap<>();
    private final Set<String> ephemeralCardWarnedAgents = ConcurrentHashMap.newKeySet();

    /**
     * Constructs the gateway caller.
     *
     * @param properties    the gateway properties
     * @param cardResolver  the gateway card resolver (for URL construction)
     */
    public A2AGatewayRemoteAgentCaller(A2AGatewayProperties properties,
            RemoteAgentCardResolver cardResolver) {
        this.properties = properties;
        this.cardResolver = cardResolver;
        this.httpClient = new JdkA2AHttpClient();
    }

    @Override
    public void call(RemoteAgentCall call, QueryStreamObserver observer) {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            observer.onError(new RemoteAgentException(
                    "A2AGatewayRemoteAgentCaller: openjiuwen.service.a2a-gateway.token is not configured",
                    null));
            return;
        }
        String jsonRpcUrl = cardResolver.resolveJsonRpcUrl(call.agentId());
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            observer.onError(new RemoteAgentException(
                    "A2AGatewayRemoteAgentCaller: cannot resolve JSON-RPC URL for agentId=" + call.agentId(),
                    null));
            return;
        }

        ServeRequest forwarded = ForwardedServeRequests.build(call.serveRequest(), call.responseContent());
        String contextId = call.contextId() != null
                ? call.contextId()
                : UUID.randomUUID().toString();
        String messageText = call.message() != null && !call.message().isBlank()
                ? call.message() : forwarded.lastUserQuery();

        log.info("A2AGateway call agent={} url={} userId={} responseContentLen={} appendedMessages={}",
                call.agentId(), jsonRpcUrl, forwarded.getUserId(),
                call.responseContent() != null ? call.responseContent().length() : 0,
                forwarded.getMessages().size() - call.serveRequest().getMessages().size());

        Message.Builder msgBuilder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .contextId(contextId)
                .parts(List.<Part<?>>of(new TextPart(messageText)));
        if (call.taskId() != null && !call.taskId().isBlank()) {
            msgBuilder.taskId(call.taskId());
        }
        Message message = msgBuilder.build();
        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(forwarded.getMetadata())
                .build();

        AgentCard card = buildEphemeralCard(call.agentId(), jsonRpcUrl);
        Client client = getOrCreateClient(card);
        ClientCallContext context = new ClientCallContext(Map.of(), buildHeaders(forwarded.getUserId()));

        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
                log.debug("A2AGateway callback agentId={} eventClass={} taskState={}",
                        call.agentId(), event.getClass().getSimpleName(),
                        event instanceof TaskEvent te && te.getTask() != null && te.getTask().status() != null
                                ? te.getTask().status().state() : "n/a");
                if (event instanceof TaskUpdateEvent tue) {
                    if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                        handleArtifact(aue, result, observer);
                    } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                        handleStatusUpdate(sue, result, observer);
                    }
                } else if (event instanceof TaskEvent te) {
                    handleTaskEvent(te, result, observer);
                }
            }), result::completeExceptionally, context);
        } catch (RuntimeException ex) {
            observer.onError(new RemoteAgentException(
                    "A2AGateway call failed for agentId=" + call.agentId(), ex));
            return;
        }

        long timeoutSeconds = properties.getCallTimeoutSeconds();
        try {
            String answer = result.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("A2AGateway call completed agentId={} answerLen={} answerHead={}",
                    call.agentId(), answer == null ? 0 : answer.length(),
                    answer == null ? "" : answer.substring(0, Math.min(answer.length(), 80)));
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (TimeoutException e) {
            observer.onError(new RemoteAgentException(
                    "A2AGateway call timed out for agentId=" + call.agentId()
                            + " after " + timeoutSeconds + "s", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observer.onError(new RemoteAgentException(
                    "A2AGateway call interrupted for agentId=" + call.agentId(), e));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                        Map.of("message", rie.getMessage() == null ? "" : rie.getMessage(),
                                "remote_task_id", rie.getRemoteTaskId() == null ? "" : rie.getRemoteTaskId())));
                if (!observer.isCancelled()) {
                    observer.onComplete();
                }
            } else {
                observer.onError(new RemoteAgentException(
                        "A2AGateway call failed for agentId=" + call.agentId(), e.getCause()));
            }
        }
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && !agentId.isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getToken() != null && !properties.getToken().isBlank();
    }

    /**
     * Builds the per-call HTTP headers injected via {@link ClientCallContext}.
     *
     * <p>Tracing headers ({@code X-B3-TraceId}, {@code X-B3-Sampled},
     * {@code X-Biz-Tag}) and the parent span id (derived from the upstream
     * {@code X-B3-SpanId}) are propagated from the current upstream HTTP
     * request via {@link RequestContextHolder}; a fresh
     * {@code X-B3-SpanId} is generated per call.
     *
     * <p>Package-private for test access.
     *
     * @param userId the upstream user id (may be {@code null})
     * @return a mutable map of header name to value
     */
    Map<String, String> buildHeaders(String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_TOKEN, properties.getToken());
        if (userId != null && !userId.isBlank()) {
            headers.put(HEADER_USER_ID, userId);
        }
        if (properties.getVersionNode() != null && !properties.getVersionNode().isBlank()) {
            headers.put(HEADER_VERSION_NODE, properties.getVersionNode());
        }

        String upstreamTraceId = resolveUpstreamHeader(HEADER_B3_TRACE_ID);
        if (upstreamTraceId != null && !upstreamTraceId.isBlank()) {
            headers.put(HEADER_B3_TRACE_ID, upstreamTraceId);
        }

        String upstreamSampled = resolveUpstreamHeader(HEADER_B3_SAMPLED);
        if (upstreamSampled != null && !upstreamSampled.isBlank()) {
            headers.put(HEADER_B3_SAMPLED, upstreamSampled);
        }

        String upstreamBizTag = resolveUpstreamHeader(HEADER_BIZ_TAG);
        if (upstreamBizTag != null && !upstreamBizTag.isBlank()) {
            headers.put(HEADER_BIZ_TAG, upstreamBizTag);
        }

        String upstreamSpanId = resolveUpstreamHeader(HEADER_B3_SPAN_ID);
        if (upstreamSpanId != null && !upstreamSpanId.isBlank()) {
            headers.put(HEADER_B3_PARENT_SPAN_ID, upstreamSpanId);
        }

        headers.put(HEADER_B3_SPAN_ID, generateSpanId());

        return headers;
    }

    /**
     * Resolves a header from the current upstream HTTP request, if any.
     *
     * <p>Package-private for test override (the default implementation reads
     * from {@link RequestContextHolder}, which is empty in unit tests without
     * a servlet request scope).
     *
     * @param name the header name (case-insensitive per servlet spec)
     * @return the header value, or {@code null} if no upstream request is
     *         bound or the header is absent
     */
    String resolveUpstreamHeader(String name) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        return request.getHeader(name);
    }

    /**
     * Generates a fresh span id for this outbound call. Uses
     * {@link UUID#randomUUID()} with dashes stripped (32 hex chars), as
     * permitted by the B3 extension spec for 128-bit span ids.
     */
    String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Builds an ephemeral {@link AgentCard} for the A2A Gateway route.
     *
     * <p>The gateway caller does NOT fetch the real Agent Card from
     * {@code gatewayBaseUrl/{agentCard}/.well-known/agent-card.json}. This is
     * a deliberate simplification for the gateway routing mode: the gateway
     * itself handles target routing, and whether it exposes a standard card
     * endpoint per agent is an open deployment question (PRD TBD-04/07).
     * Consequences:
     * <ul>
     *   <li>Protocol version is fixed to {@link AgentInterface#CURRENT_PROTOCOL_VERSION};
     *       if the gateway-bridged target speaks an older version, calls fail
     *       at the SDK layer rather than being filtered up front.</li>
     *   <li>FEAT-015 capability/skill negotiation is skipped. Deployments that
     *       need compatibility filtering must configure the gateway to expose
     *       real cards and switch to a caller implementation that fetches them.</li>
     * </ul>
     *
     * <p>A WARN is logged once per agentId to surface this assumption.
     *
     * <p>此简化对应 L2 §4.9.6 落地范围：gateway 路由模式 URL 模板为
     * {@code gatewayBaseUrl + "/" + agentId + jsonRpcPath}，
     * {@code A2AGatewayCardResolver.resolveCardUrl} 永远返回空串，故
     * FEAT-015 card fetch 与协议版本协商在 gateway 路由模式下被刻意跳过。
     * 若部署需要能力/技能过滤或目标 Agent 协议版本低于 CURRENT，应切换到
     * Default 路由模式或扩展 resolver 支持 real card fetch。
     */
    AgentCard buildEphemeralCard(String agentId, String jsonRpcUrl) {
        if (ephemeralCardWarnedAgents.add(agentId)) {
            log.warn("A2AGateway caller using ephemeral AgentCard for agentId={} "
                    + "(protocol version pinned to CURRENT; FEAT-015 card fetch skipped)", agentId);
        }
        return AgentCard.builder()
                .name(agentId)
                .description("A2A Gateway route to " + agentId)
                .version("0.1.0")
                .url(jsonRpcUrl)
                .capabilities(AgentCapabilities.builder().streaming(properties.isStreaming()).build())
                .skills(List.of())
                .defaultInputModes(List.of())
                .defaultOutputModes(List.of())
                .supportedInterfaces(List.of(
                        new AgentInterface(PROTOCOL_BINDING_JSONRPC, jsonRpcUrl, null,
                                AgentInterface.CURRENT_PROTOCOL_VERSION)))
                .build();
    }

    private Client getOrCreateClient(AgentCard card) {
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(
                card.name(),
                k -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(properties.isStreaming()).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig(httpClient))
                        .build()));
    }

    /**
     * Releases cached {@link Client} instances (each holding HTTP connection
     * pool state) on bean destruction. Without this, context restarts / hot
     * reloads leak connections until JVM exit.
     */
    @PreDestroy
    void shutdown() {
        for (Client client : clientCache.values()) {
            try {
                client.close();
            } catch (RuntimeException ex) {
                log.debug("A2AGateway caller: error closing cached client", ex);
            }
        }
        clientCache.clear();
    }

    private static <T> T withApplicationClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader appCl = A2AGatewayRemoteAgentCaller.class.getClassLoader();
        if (appCl == null || original == appCl) {
            return action.get();
        }
        try {
            thread.setContextClassLoader(appCl);
            return action.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<String> result,
            QueryStreamObserver observer) {
        if (result.isDone()) {
            return;
        }
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) {
            return;
        }
        String raw = extractText(a.parts());
        if (raw.isEmpty()) {
            return;
        }
        String answer = RemoteAgentAnswerExtractor.extractAnswer(raw).orElse(raw);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "answer");
        envelope.put("output", answer);
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
        RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(ans -> {
            if (!result.isDone()) {
                result.complete(ans);
            }
        });
    }

    private void handleStatusUpdate(TaskStatusUpdateEvent sue, CompletableFuture<String> result,
            QueryStreamObserver observer) {
        if (sue.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = sue.status().message() != null
                    ? extractText(sue.status().message().parts()) : "";
            if (!result.isDone()) {
                result.completeExceptionally(new RemoteInputRequiredException(
                        statusText.isBlank() ? "Remote agent requires input" : statusText,
                        sue.taskId() != null ? sue.taskId() : ""));
            }
        } else if (sue.status().state().isFinal() && !result.isDone()) {
            result.complete("");
        }
    }

    private void handleTaskEvent(TaskEvent te, CompletableFuture<String> result,
            QueryStreamObserver observer) {
        if (result.isDone()) {
            return;
        }
        Task task = te.getTask();
        if (task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
            String statusText = task.status().message() != null
                    ? extractText(task.status().message().parts()) : "";
            result.completeExceptionally(new RemoteInputRequiredException(
                    statusText.isBlank() ? "Remote agent requires input" : statusText,
                    task.id() != null ? task.id() : ""));
        } else if (task.status().state().isFinal()) {
            if (task.artifacts() != null) {
                for (Artifact a : task.artifacts()) {
                    if (a == null || a.parts() == null) {
                        continue;
                    }
                    String raw = extractText(a.parts());
                    if (!raw.isEmpty()) {
                        String answer = RemoteAgentAnswerExtractor.extractAnswer(raw).orElse(raw);
                        Map<String, Object> envelope = new LinkedHashMap<>();
                        envelope.put("type", "answer");
                        envelope.put("output", answer);
                        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
                    }
                }
            }
            String text = task.artifacts() != null && !task.artifacts().isEmpty()
                    ? extractText(task.artifacts().get(0).parts()) : "";
            result.complete(text);
        }
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Part<?> p : parts) {
            if (p instanceof TextPart tp) {
                sb.append(tp.text());
            }
        }
        return sb.toString();
    }
}
