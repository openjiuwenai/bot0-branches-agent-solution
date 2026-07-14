/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfig;
import org.a2aproject.sdk.spec.A2AClientError;
import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Streams an outbound A2A request to the plan-agent through the a2a-java SDK
 * {@link Client} and pipes the resulting events to the caller's output stream.
 *
 * <p>Each streaming event is dispatched by type:
 * <ul>
 *   <li>{@link TaskStatusUpdateEvent} — consumed for resume-state bookkeeping
 *       ({@link ResumeStateStore#recordInputRequired} / {@link ResumeStateStore#clear})
 *       and NEVER forwarded to the client. Terminates the stream when
 *       {@code isFinalOrInterrupted()} returns true.</li>
 *   <li>{@link TaskArtifactUpdateEvent} — normalized by {@link A2aArtifactNormalizer}
 *       into EDPA {@code {"event":...,"data":{...}}} envelopes (one per text part) so the
 *       framework's SSE parser can capture the business payload.</li>
 *   <li>{@link TaskEvent} (initial task snapshot) / {@link MessageEvent} — logged and dropped;
 *       neither is expected on this pipeline.</li>
 * </ul>
 *
 * @since 2026-07-09
 */
@Component
public class A2aStreamingClient {
    private static final Logger LOG = LoggerFactory.getLogger(A2aStreamingClient.class);

    private static final long STREAM_TIMEOUT_SECONDS = 300L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Client> clientRef = new AtomicReference<>();

    private final GatewayProperties properties;
    private final ResumeStateStore store;

    public A2aStreamingClient(GatewayProperties properties, ResumeStateStore store) {
        this.properties = properties;
        this.store = store;
    }

    /**
     * Send {@code params} to the plan-agent and stream the SSE reply into {@code clientOutput}.
     * Blocks until the upstream stream ends (final or interrupted status) or an error is signalled.
     *
     * @param params       the A2A message send params to post to the plan-agent
     * @param clientOutput the response output stream the SSE reply is written into
     * @throws IOException          if writing to {@code clientOutput} or reading the upstream fails
     * @throws InterruptedException if the calling thread is interrupted while streaming
     */
    public void streamPost(MessageSendParams params, OutputStream clientOutput)
            throws IOException, InterruptedException {
        Client client = getOrInitClient();
        Writer out = new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        BiConsumer<ClientEvent, AgentCard> consumer = (event, card) -> {
            try {
                dispatch(event, out, done);
            } catch (java.io.UncheckedIOException e) {
                errorRef.compareAndSet(null, e);
                done.countDown();
            }
        };
        Consumer<Throwable> errorHandler = err -> {
            // SDK's AbstractSSEEventListener cancels the underlying HTTP future when
            // the upstream SSE stream terminates — either after a final/interrupted
            // TaskStatusUpdateEvent (see AbstractSSEEventListener line 100) OR simply
            // when plan-agent closes the connection without emitting a terminal
            // status frame (the common case here — plan-agent signals end-of-round
            // via the artifact-level {"event":"end"} envelope, not via A2A status).
            // Either way, CancellationException here is a normal end-of-stream
            // signal, not an error. Count the latch down and drop the throwable.
            if (isCancellation(err)) {
                LOG.debug("<<< plan-agent stream closed via SDK cancellation", err);
                if (done.getCount() > 0) {
                    done.countDown();
                }
                return;
            }
            LOG.warn("<<< plan-agent stream error [{}]", err.getClass().getName(), err);
            errorRef.compareAndSet(null, err);
            done.countDown();
        };

        logOutboundMessage(params);
        try {
            client.sendMessage(params, List.of(consumer), errorHandler, null);
        } catch (A2AClientException e) {
            throw new IOException("Failed to initiate plan-agent stream", e);
        }

        if (!done.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Timeout waiting for plan-agent stream to complete");
        }
        Throwable err = errorRef.get();
        if (err != null) {
            if (err instanceof IOException io) {
                throw io;
            }
            throw new IOException(err);
        }
        out.flush();
    }

    private void dispatch(ClientEvent event, Writer out, CountDownLatch done) {
        if (event instanceof TaskUpdateEvent tue) {
            Object update = tue.getUpdateEvent();
            if (update instanceof TaskStatusUpdateEvent status) {
                handleStatus(status);
                if (status.isFinalOrInterrupted()) {
                    done.countDown();
                }
            } else if (update instanceof TaskArtifactUpdateEvent artifact) {
                for (String envelope : A2aArtifactNormalizer.normalize(mapper, artifact)) {
                    LOG.info("<<< SSE frame from plan-agent: {}", envelope);
                    forwardFrame(out, envelope);
                }
            } else if (update != null) {
                LOG.info("<<< dropping unrecognized update event {}", update.getClass().getSimpleName());
            } else {
                // update payload is null — nothing to dispatch
                LOG.debug("<<< task update event with no update payload");
            }
        } else if (event instanceof TaskEvent te) {
            LOG.info("<<< initial task event taskId={} contextId={}",
                    te.getTask().id(), te.getTask().contextId());
        } else if (event instanceof MessageEvent me) {
            LOG.info("<<< dropping unexpected message event messageId={}", me.getMessage().messageId());
        } else {
            LOG.info("<<< dropping unrecognized client event {}",
                    event == null ? "null" : event.getClass().getSimpleName());
        }
    }

    private void handleStatus(TaskStatusUpdateEvent event) {
        String contextId = event.contextId();
        String taskId = event.taskId();
        TaskState state = event.status() == null ? null : event.status().state();
        if (contextId == null || state == null) {
            return;
        }
        if (state == TaskState.TASK_STATE_INPUT_REQUIRED) {
            store.recordInputRequired(contextId, taskId);
            LOG.debug("recorded INPUT_REQUIRED contextId={} taskId={}", contextId, taskId);
        } else if (state.isFinal()) {
            store.clear(contextId);
            LOG.debug("cleared resume state on terminal {} contextId={}", state, contextId);
        } else {
            // intermediate state (e.g. WORKING) — no resume-state bookkeeping needed
            LOG.debug("non-terminal state {} contextId={}", state, contextId);
        }
    }

    private static boolean isCancellation(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    private void logOutboundMessage(MessageSendParams params) {
        // Render the request in the A2A 1.0.0 JSON-RPC wire shape — jsonrpc/method/params with the
        // message nested under params.message and the gateway metadata under params.metadata — so the
        // log mirrors what the SDK actually posts to the plan-agent. The previous layout was the 0.3.x
        // message/stream envelope with every field flattened and a fabricated part `kind`.
        Message msg = params.message();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", msg.role() == null ? null : msg.role().toString());
        putIfPresent(message, "contextId", msg.contextId());
        putIfPresent(message, "messageId", msg.messageId());
        putIfPresent(message, "taskId", msg.taskId());
        List<Object> parts = new ArrayList<>();
        for (Part<?> part : msg.parts()) {
            if (part instanceof TextPart tp) {
                // A2A 1.0.0 TextPart serializes as {"text": ...}; the SDK record carries no `kind`
                // discriminator, so emit the payload verbatim rather than inventing one.
                Map<String, Object> textPart = new LinkedHashMap<>();
                textPart.put("text", tp.text());
                parts.add(textPart);
            } else {
                // This pipeline only ever emits TextParts; serialize anything else verbatim.
                parts.add(part);
            }
        }
        message.put("parts", parts);

        Map<String, Object> rpcParams = new LinkedHashMap<>();
        rpcParams.put("message", message);
        rpcParams.put("metadata", params.metadata());

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "SendStreamingMessage");
        request.put("params", rpcParams);

        String bodyJson;
        try {
            bodyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
        } catch (JsonProcessingException e) {
            LOG.warn(">>> A2A outbound message serialization failed", e);
            return;
        }
        LOG.info(">>> sending A2A message to plan-agent contextId={} taskId={} - outbound body:\n{}",
                msg.contextId(), msg.taskId(), bodyJson);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void forwardFrame(Writer out, String payload) {
        try {
            out.write("data: ");
            out.write(payload);
            out.write("\n\n");
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Client getOrInitClient() throws IOException {
        Client client = clientRef.get();
        if (client != null) {
            return client;
        }
        synchronized (clientRef) {
            client = clientRef.get();
            if (client != null) {
                return client;
            }
            String baseUrl = properties.planAgentBaseUrl();
            LOG.info("initializing a2a SDK client from AgentCard at {}", baseUrl);
            try {
                AgentCard card = A2A.getAgentCard(baseUrl);
                ClientConfig config = new ClientConfig.Builder()
                        .setStreaming(true)
                        .setAcceptedOutputModes(List.of("text"))
                        .build();
                client = Client.builder(card)
                        .clientConfig(config)
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
                        .build();
            } catch (A2AClientError | A2AClientException e) {
                throw new IOException("Failed to resolve plan-agent AgentCard from " + baseUrl, e);
            }
            clientRef.set(client);
            return client;
        }
    }
}
