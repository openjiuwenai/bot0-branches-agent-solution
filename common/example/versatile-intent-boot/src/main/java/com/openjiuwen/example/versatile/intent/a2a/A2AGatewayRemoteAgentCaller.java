/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentAnswerExtractor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
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
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A2A Gateway {@link RemoteAgentCaller} implementation: routes via the A2A SDK
 * {@link Client} to {@code gatewayBaseUrl + "/a2a/" + agentName} (resolved by
 * {@link A2AGatewayCardResolver}) and consumes
 * {@link RemoteCall#message()} as the cross-layer payload.
 *
 * <p>Per the gateway's integration requirements, every request carries:
 * <ul>
 *   <li>{@code token} header — service credential from
 *       {@link A2AGatewayProperties#getToken()} (required; call fails fast if absent)</li>
 *   <li>{@code userId} header — propagated from
 *       {@link RemoteCall#metadata()} under key {@code userId} when present</li>
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
 * <p>The ephemeral {@link AgentCard} built per agentName uses the uppercase
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
     * Metadata key carrying the upstream {@code userId} when the orchestrator
     * propagates it via {@link RemoteCall#metadata()}.
     */
    static final String METADATA_KEY_USER_ID = "userId";

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
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver) {
        String agentName = call.agentName();
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            return CompletableFuture.failedFuture(new RemoteAgentException(
                    "A2AGatewayRemoteAgentCaller: openjiuwen.service.a2a-gateway.token is not configured",
                    null));
        }
        String jsonRpcUrl = cardResolver.resolveJsonRpcUrl(agentName);
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            return CompletableFuture.failedFuture(new RemoteAgentException(
                    "A2AGatewayRemoteAgentCaller: cannot resolve JSON-RPC URL for agentId=" + agentName,
                    null));
        }

        boolean streaming = properties.isStreaming();
        String contextId = call.contextId() != null
                ? call.contextId()
                : UUID.randomUUID().toString();
        String messageText = call.message() != null ? call.message() : "";
        String userId = resolveUserId(call);

        log.info("A2AGateway call agent={} url={} userId={} messageLen={}",
                agentName, jsonRpcUrl, userId, messageText.length());

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
                .metadata(call.metadata())
                .build();

        AgentCard card = buildEphemeralCard(agentName, jsonRpcUrl, streaming);
        Client client = getOrCreateClient(card, streaming);
        ClientCallContext context = new ClientCallContext(Map.of(), buildHeaders(userId));

        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(properties.getCallTimeoutSeconds(), TimeUnit.SECONDS);
        BiConsumer<ClientEvent, AgentCard> eventConsumer = (event, ignoredCard) ->
                handleClientEvent(event, agentName, result, streamObserver, remoteTaskIdObserver);
        try {
            client.sendMessage(params, List.of(eventConsumer),
                    error -> completeOnStreamEnd(agentName, result, error), context);
        } catch (RuntimeException ex) {
            result.completeExceptionally(new RemoteAgentException(
                    "A2AGateway call failed for agentId=" + agentName, ex));
        }
        return result;
    }

    @Override
    public boolean supported(String agentName) {
        return agentName != null && !agentName.isBlank()
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getToken() != null && !properties.getToken().isBlank();
    }

    private static String resolveUserId(RemoteCall call) {
        Map<String, Object> metadata = call.metadata();
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(METADATA_KEY_USER_ID);
        return value instanceof String uid && !uid.isBlank() ? uid : null;
    }

    private void handleClientEvent(ClientEvent event, String agentName,
            CompletableFuture<RemoteCallOutcome> result, QueryStreamObserver streamObserver,
            Consumer<String> remoteTaskIdObserver) {
        if (result.isDone()) {
            return;
        }
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                TaskState state = tue.getTask() != null && tue.getTask().status() != null
                        ? tue.getTask().status().state() : null;
                notifyRemoteTaskId(remoteTaskIdObserver, aue.taskId(), state);
                handleArtifact(aue, result, streamObserver);
            } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                handleStatusUpdate(sue, result, streamObserver, remoteTaskIdObserver);
            }
        } else if (event instanceof TaskEvent te) {
            handleTaskEvent(te, result, streamObserver, remoteTaskIdObserver);
        }
    }

    private static boolean completeOnStreamEnd(String agentName, CompletableFuture<RemoteCallOutcome> result,
            Throwable error) {
        if (result.isDone()) {
            return false;
        }
        Throwable failure = error == null
                ? new RemoteAgentException(
                        "A2AGateway call for agentId=" + agentName
                                + " closed the stream before a terminal event", null)
                : error;
        return result.completeExceptionally(failure);
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
    AgentCard buildEphemeralCard(String agentId, String jsonRpcUrl, boolean streaming) {
        if (ephemeralCardWarnedAgents.add(agentId)) {
            log.warn("A2AGateway caller using ephemeral AgentCard for agentId={} "
                    + "(protocol version pinned to CURRENT; FEAT-015 card fetch skipped)", agentId);
        }
        return AgentCard.builder()
                .name(agentId)
                .description("A2A Gateway route to " + agentId)
                .version("0.1.0")
                .url(jsonRpcUrl)
                .capabilities(AgentCapabilities.builder().streaming(streaming).build())
                .skills(List.of())
                .defaultInputModes(List.of())
                .defaultOutputModes(List.of())
                .supportedInterfaces(List.of(
                        new AgentInterface(PROTOCOL_BINDING_JSONRPC, jsonRpcUrl, null,
                                AgentInterface.CURRENT_PROTOCOL_VERSION)))
                .build();
    }

    private Client getOrCreateClient(AgentCard card, boolean streaming) {
        String cacheKey = card.name() + ":" + streaming;
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(
                cacheKey,
                k -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(streaming).build())
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

    private void handleArtifact(TaskArtifactUpdateEvent aue, CompletableFuture<RemoteCallOutcome> result,
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
        if (observer != null) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "answer");
            envelope.put("output", answer);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
        }
        RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(ans -> {
            if (!result.isDone()) {
                result.complete(new RemoteCallOutcome(aue.taskId(), TaskState.TASK_STATE_COMPLETED,
                        "COMPLETED", ans, null));
            }
        });
    }

    private void handleStatusUpdate(TaskStatusUpdateEvent sue, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver observer, Consumer<String> remoteTaskIdObserver) {
        TaskState state = sue.status().state();
        notifyRemoteTaskId(remoteTaskIdObserver, sue.taskId(), state);
        if (result.isDone()) {
            return;
        }
        if (state.isInterrupted()) {
            String statusText = sue.status().message() != null
                    ? extractText(sue.status().message().parts()) : "";
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            if (observer != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("message", inputPrompt);
                payload.put("remote_task_id", sue.taskId() != null ? sue.taskId() : "");
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, payload));
            }
            result.complete(new RemoteCallOutcome(sue.taskId(), state, resultCategory(state),
                    null, inputPrompt));
        } else if (state.isFinal()) {
            result.complete(new RemoteCallOutcome(sue.taskId(), state, resultCategory(state), "", null));
        }
    }

    private void handleTaskEvent(TaskEvent te, CompletableFuture<RemoteCallOutcome> result,
            QueryStreamObserver observer, Consumer<String> remoteTaskIdObserver) {
        Task task = te.getTask();
        TaskState state = task.status().state();
        notifyRemoteTaskId(remoteTaskIdObserver, task.id(), state);
        if (result.isDone()) {
            return;
        }
        if (state.isInterrupted()) {
            String statusText = task.status().message() != null
                    ? extractText(task.status().message().parts()) : "";
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            if (observer != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("message", inputPrompt);
                payload.put("remote_task_id", task.id() != null ? task.id() : "");
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, payload));
            }
            result.complete(new RemoteCallOutcome(task.id(), state, resultCategory(state),
                    null, inputPrompt));
        } else if (state.isFinal()) {
            String text = "";
            if (task.artifacts() != null && !task.artifacts().isEmpty()) {
                text = extractText(task.artifacts().get(0).parts());
                for (Artifact a : task.artifacts()) {
                    if (a == null || a.parts() == null) {
                        continue;
                    }
                    String raw = extractText(a.parts());
                    if (!raw.isEmpty() && observer != null) {
                        String answer = RemoteAgentAnswerExtractor.extractAnswer(raw).orElse(raw);
                        Map<String, Object> envelope = new LinkedHashMap<>();
                        envelope.put("type", "answer");
                        envelope.put("output", answer);
                        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
                    }
                }
            }
            String answer = RemoteAgentAnswerExtractor.extractAnswer(text).orElse(text);
            result.complete(new RemoteCallOutcome(task.id(), state, resultCategory(state), answer, null));
        }
    }

    private static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_PROTOCOL_ERROR";
        };
    }

    private static void notifyRemoteTaskId(Consumer<String> observer, String remoteTaskId, TaskState state) {
        if (observer == null) {
            return;
        }
        try {
            observer.accept(remoteTaskId);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            log.warn("Remote task ID observer rejected update taskId={} state={}", remoteTaskId, state, ex);
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
