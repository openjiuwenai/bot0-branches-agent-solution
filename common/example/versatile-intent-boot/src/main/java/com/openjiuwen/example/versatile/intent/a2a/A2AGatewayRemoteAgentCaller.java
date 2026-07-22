/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentAnswerExtractor;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteInputRequiredException;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * A2A Gateway {@link RemoteAgentCaller} implementation: routes via
 * {@code gatewayBaseUrl + "/" + agentId + jsonRpcPath} (resolved by
 * {@link A2AGatewayCardResolver}) and consumes
 * {@link RemoteAgentCall#responseContent()} to append an assistant message
 * to the forwarded {@link ServeRequest#messages}.
 *
 * <p>Production deployment form. Activated by
 * {@code openjiuwen.service.a2a-gateway.enabled=true}.
 *
 * @since 0.1.0
 */
public class A2AGatewayRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(A2AGatewayRemoteAgentCaller.class);

    private static final long CALL_TIMEOUT_SECONDS = 300L;

    private final A2AGatewayProperties properties;
    private final RemoteAgentCardResolver cardResolver;
    private final Map<String, Client> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

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
    }

    @Override
    public void call(RemoteAgentCall call, QueryStreamObserver observer) {
        ServeRequest forwarded = buildForwardedServeRequest(call.serveRequest(), call.responseContent());
        String jsonRpcUrl = cardResolver.resolveJsonRpcUrl(call.agentId());
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            observer.onError(new RemoteAgentException(
                    "A2AGatewayRemoteAgentCaller: cannot resolve JSON-RPC URL for agentId=" + call.agentId(),
                    null));
            return;
        }
        String contextId = call.contextId() != null
                ? call.contextId()
                : UUID.randomUUID().toString();
        String messageText = call.message() != null && !call.message().isBlank()
                ? call.message() : forwarded.lastUserQuery();
        log.info("A2AGateway call agent={} url={} responseContentLen={} appendedMessages={}",
                call.agentId(), jsonRpcUrl,
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
        Client client = createClient(card, true);
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            client.sendMessage(params, List.of((BiConsumer<ClientEvent, AgentCard>) (event, c) -> {
                if (event instanceof TaskUpdateEvent tue) {
                    if (tue.getUpdateEvent() instanceof TaskArtifactUpdateEvent aue) {
                        handleArtifact(aue, result, observer);
                    } else if (tue.getUpdateEvent() instanceof TaskStatusUpdateEvent sue) {
                        handleStatusUpdate(sue, result, observer);
                    }
                } else if (event instanceof TaskEvent te) {
                    handleTaskEvent(te, result, observer);
                }
            }), result::completeExceptionally, null);
        } catch (RuntimeException ex) {
            observer.onError(new RemoteAgentException(
                    "A2AGateway call failed for agentId=" + call.agentId(), ex));
            return;
        }

        try {
            result.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (TimeoutException e) {
            observer.onError(new RemoteAgentException(
                    "A2AGateway call timed out for agentId=" + call.agentId()
                            + " after " + CALL_TIMEOUT_SECONDS + "s", e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observer.onError(new RemoteAgentException(
                    "A2AGateway call interrupted for agentId=" + call.agentId(), e));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RemoteInputRequiredException rie) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                        Map.of("message", rie.getMessage(), "remote_task_id", rie.getRemoteTaskId())));
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
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    /**
     * Builds a forwarded ServeRequest by copying the original and appending
     * {@code responseContent} as an assistant message when non-null and non-blank.
     *
     * <p>Package-private for testability.
     *
     * @param original        the original serve request
     * @param responseContent optional upstream response content
     * @return a new serve request with messages appended
     */
    static ServeRequest buildForwardedServeRequest(ServeRequest original, String responseContent) {
        ServeRequest forwarded = new ServeRequest();
        forwarded.setConversationId(original.getConversationId());
        forwarded.setUserId(original.getUserId());
        forwarded.setSpaceId(original.getSpaceId());
        forwarded.setTenantId(original.getTenantId());
        forwarded.setStream(original.isStream());
        forwarded.setMetadata(original.getMetadata());
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        if (responseContent != null && !responseContent.isBlank()) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", responseContent);
            messages.add(assistant);
        }
        forwarded.setMessages(messages);
        return forwarded;
    }

    private AgentCard buildEphemeralCard(String agentId, String jsonRpcUrl) {
        return AgentCard.builder()
                .name(agentId)
                .description("A2A Gateway route to " + agentId)
                .version("0.1.0")
                .url(jsonRpcUrl)
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .skills(List.of())
                .supportedInterfaces(List.of(
                        new AgentInterface("jsonrpc", jsonRpcUrl, null, AgentInterface.CURRENT_PROTOCOL_VERSION)))
                .build();
    }

    private Client createClient(AgentCard card, boolean isStreaming) {
        return withApplicationClassLoader(() -> clientCache.computeIfAbsent(
                card.name() + ":" + isStreaming,
                k -> Client.builder(card)
                        .clientConfig(new ClientConfig.Builder().setStreaming(isStreaming).build())
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                        .build()));
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
        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, raw));
        RemoteAgentAnswerExtractor.extractAnswer(raw).ifPresent(answer -> {
            if (!result.isDone()) {
                result.complete(answer);
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
