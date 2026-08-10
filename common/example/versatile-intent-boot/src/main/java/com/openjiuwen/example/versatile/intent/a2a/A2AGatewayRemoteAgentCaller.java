/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller.EventObserver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import jakarta.annotation.PreDestroy;

import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
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

    private static final Logger log = LoggerFactory.getLogger(A2AGatewayRemoteAgentCaller.class);
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
            EventObserver eventObserver) {
        String agentName = call.agentName();
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "A2AGatewayRemoteAgentCaller: openjiuwen.service.a2a-gateway.token is not configured",
                    null));
        }
        String jsonRpcUrl = cardResolver.resolveJsonRpcUrl(agentName);
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "A2AGatewayRemoteAgentCaller: cannot resolve JSON-RPC URL for agentId=" + agentName,
                    null));
        }

        boolean streaming = properties.isStreaming();
        String contextId = call.contextId() != null
                ? call.contextId()
                : UUID.randomUUID().toString();
        String messageText = call.message() != null ? call.message() : "";
        Optional<String> userId = resolveUserId(call);

        log.info("A2AGateway call agent={} url={} userId={} messageLen={}",
                agentName, jsonRpcUrl, userId.orElse(null), messageText.length());

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
        ClientCallContext context = new ClientCallContext(Map.of(), buildHeaders(userId.orElse(null)));

        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(properties.getCallTimeoutSeconds(), TimeUnit.SECONDS);
        BiConsumer<ClientEvent, AgentCard> eventConsumer = (event, ignoredCard) -> {
            try {
                handleClientEvent(event, agentName, result, eventObserver, streaming);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        };
        try {
            client.sendMessage(params, List.of(eventConsumer),
                    error -> completeOnStreamEnd(agentName, result, error), context);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            result.completeExceptionally(new IllegalStateException(
                    "A2AGateway call failed for agentId=" + agentName, ex));
        }
        return result;
    }

    private static Optional<String> resolveUserId(RemoteCall call) {
        Map<String, Object> metadata = call.metadata();
        if (metadata == null) {
            return Optional.empty();
        }
        Object value = metadata.get(METADATA_KEY_USER_ID);
        return value instanceof String uid && !uid.isBlank() ? Optional.of(uid) : Optional.empty();
    }

    private void handleClientEvent(ClientEvent event, String agentName,
            CompletableFuture<RemoteCallOutcome> result, EventObserver eventObserver, boolean streaming) {
        if (result.isDone()) {
            return;
        }
        if (event instanceof TaskUpdateEvent tue) {
            if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                handleArtifact(aue, result, eventObserver);
            } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                handleStatusUpdate(sue, tue.getTask(), result, eventObserver);
            } else {
                log.debug("A2AGateway caller: unrecognized TaskUpdateEvent payload class={}",
                        tue.getUpdateEvent() != null ? tue.getUpdateEvent().getClass().getName() : "null");
            }
        } else if (event instanceof TaskEvent te) {
            handleTaskEvent(te, result, eventObserver, streaming);
        } else if (event instanceof MessageEvent me) {
            handleMessageEvent(me, result);
        } else {
            log.debug("A2AGateway caller: unrecognized ClientEvent class={}", event.getClass().getName());
        }
    }

    private static boolean completeOnStreamEnd(String agentName, CompletableFuture<RemoteCallOutcome> result,
            Throwable error) {
        if (result.isDone()) {
            return false;
        }
        Throwable failure = error == null
                ? new IllegalStateException(
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

        resolveUpstreamHeader(HEADER_B3_TRACE_ID)
                .filter(s -> !s.isBlank())
                .ifPresent(v -> headers.put(HEADER_B3_TRACE_ID, v));

        resolveUpstreamHeader(HEADER_B3_SAMPLED)
                .filter(s -> !s.isBlank())
                .ifPresent(v -> headers.put(HEADER_B3_SAMPLED, v));

        resolveUpstreamHeader(HEADER_BIZ_TAG)
                .filter(s -> !s.isBlank())
                .ifPresent(v -> headers.put(HEADER_BIZ_TAG, v));

        resolveUpstreamHeader(HEADER_B3_SPAN_ID)
                .filter(s -> !s.isBlank())
                .ifPresent(v -> headers.put(HEADER_B3_PARENT_SPAN_ID, v));

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
     * @return the header value, or empty if no upstream request is bound or the header is absent
     */
    Optional<String> resolveUpstreamHeader(String name) {
        Object attrsObj = RequestContextHolder.getRequestAttributes();
        if (!(attrsObj instanceof ServletRequestAttributes attrs)) {
            return Optional.empty();
        }
        return Optional.ofNullable(attrs.getRequest().getHeader(name));
    }

    /**
     * Generates a fresh span id for this outbound call. Uses
     * {@link UUID#randomUUID()} with dashes stripped (32 hex chars), as
     * permitted by the B3 extension spec for 128-bit span ids.
     *
     * @return a 32-character hex span id with no dashes
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
     *
     * @param agentId the agent identifier to route to
     * @param jsonRpcUrl the full JSON-RPC URL for the gateway route
     * @param streaming whether the caller requested streaming transport
     * @return an ephemeral {@link AgentCard} for the A2A Gateway route
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
            } catch (IllegalStateException ex) {
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
            EventObserver observer) {
        if (result.isDone()) {
            return;
        }
        Artifact a = aue.artifact();
        if (a == null || a.parts() == null) {
            return;
        }
        observer.onArtifact(aue);
    }

    private void handleStatusUpdate(TaskStatusUpdateEvent sue, Task task, CompletableFuture<RemoteCallOutcome> result,
            EventObserver observer) {
        if (result.isDone()) {
            return;
        }
        TaskState state = sue.status().state();
        observer.onStatus(sue);
        String statusText = sue.status().message() == null ? "" : extractText(sue.status().message().parts());
        if (state.isInterrupted()) {
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            result.complete(new RemoteCallOutcome(sue.taskId(), state, resultCategory(state),
                    null, inputPrompt));
        } else if (state.isFinal()) {
            completeFinalTaskOutcome(sue.taskId(), state, statusText, task, result);
        } else {
            log.debug("A2AGateway caller: non-terminal status update state={}", state);
        }
    }

    private void handleTaskEvent(TaskEvent te, CompletableFuture<RemoteCallOutcome> result,
            EventObserver observer, boolean streaming) {
        if (result.isDone()) {
            return;
        }
        Task task = te.getTask();
        TaskState state = task.status().state();
        if (!streaming && task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                observer.onArtifact(new TaskArtifactUpdateEvent(task.id(), artifact, task.contextId(),
                        false, true, Map.of()));
            }
        }
        observer.onStatus(new TaskStatusUpdateEvent(task.id(), task.status(), task.contextId(), Map.of()));
        String statusText = task.status().message() == null ? "" : extractText(task.status().message().parts());
        if (state.isInterrupted()) {
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            result.complete(new RemoteCallOutcome(task.id(), state, resultCategory(state),
                    null, inputPrompt));
        } else if (state.isFinal()) {
            completeFinalTaskOutcome(task.id(), state, statusText, task, result);
        } else {
            log.debug("A2AGateway caller: non-terminal task event state={}", state);
        }
    }

    private static String resultCategory(TaskState state) {
        if (state == TaskState.TASK_STATE_COMPLETED) {
            return "COMPLETED";
        }
        if (state.isInterrupted()) {
            return "INPUT_REQUIRED";
        }
        if (state == TaskState.TASK_STATE_FAILED) {
            return "REMOTE_BUSINESS_FAILURE";
        }
        return "REMOTE_" + state.name().replaceFirst("^TASK_STATE_", "");
    }

    private static void handleMessageEvent(MessageEvent event, CompletableFuture<RemoteCallOutcome> result) {
        if (result.isDone() || event.getMessage() == null) {
            return;
        }
        Message message = event.getMessage();
        result.complete(new RemoteCallOutcome(message.taskId(), TaskState.TASK_STATE_COMPLETED, "COMPLETED",
                A2aPartContent.extract(message.parts()), null));
    }

    private static String taskResult(Task task) {
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) {
                if (artifact.metadata() != null
                        && artifact.metadata().containsKey(RemoteAgentCaller.AGENT_EVENT_METADATA)) {
                    continue;
                }
                String raw = extractText(artifact.parts());
                Optional<JsonObject> envelope = answerEnvelope(raw);
                if (envelope.isPresent() && envelope.get().has("intent_id")) {
                    return raw;
                }
            }
        }
        return A2aPartContent.extractTaskResult(task);
    }

    private static void completeFinalTaskOutcome(String taskId, TaskState state, String statusText, Task task,
            CompletableFuture<RemoteCallOutcome> result) {
        String taskText = task == null ? "" : taskResult(task);
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? statusText : taskText)
                : (statusText.isBlank() ? taskText : statusText);
        result.complete(new RemoteCallOutcome(taskId, state, resultCategory(state), resultText, null));
    }

    private static Optional<JsonObject> answerEnvelope(String raw) {
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            return envelope.has("type") && "answer".equals(envelope.get("type").getAsString())
                    ? Optional.of(envelope) : Optional.empty();
        } catch (IllegalStateException | JsonSyntaxException ex) {
            return Optional.empty();
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
