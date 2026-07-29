/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import com.openjiuwen.rdc.security.InternalNetworkPolicy;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Fetches {@code /.well-known/agent-card.json} with security boundaries (0711 §5.1.3).
 *
 * @since 0.1.0 (2026)
 */
public final class AgentCardFetcher {
    /**
     * DEFAULT_CONNECT_TIMEOUT.
     *
     * @since 0.1.0
     */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * DEFAULT_READ_TIMEOUT.
     *
     * @since 0.1.0
     */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * DEFAULT_CARD_PATH.
     *
     * @since 0.1.0
     */
    public static final String DEFAULT_CARD_PATH = "/.well-known/agent-card.json";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final AgentCardSignatureVerifier signatureVerifier;
    private final InternalNetworkPolicy networkPolicy;

    public AgentCardFetcher() {
        this(AgentCardMtlsHttpClientFactory.create(RdcCardFetchOptions.defaults()),
                DEFAULT_READ_TIMEOUT,
                AgentCardSignatureVerifier.disabled(),
                InternalNetworkPolicy.permissive());
    }

    AgentCardFetcher(HttpClient httpClient, Duration readTimeout) {
        this(httpClient, readTimeout, AgentCardSignatureVerifier.disabled(), InternalNetworkPolicy.permissive());
    }

    AgentCardFetcher(HttpClient httpClient, Duration readTimeout,
                     AgentCardSignatureVerifier signatureVerifier) {
        this(httpClient, readTimeout, signatureVerifier, InternalNetworkPolicy.permissive());
    }

    AgentCardFetcher(HttpClient httpClient, Duration readTimeout,
                     AgentCardSignatureVerifier signatureVerifier,
                     InternalNetworkPolicy networkPolicy) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.readTimeout = readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT;
        this.signatureVerifier = signatureVerifier != null
                ? signatureVerifier
                : AgentCardSignatureVerifier.disabled();
        this.networkPolicy = networkPolicy != null ? networkPolicy : InternalNetworkPolicy.permissive();
    }

    /**
     * fromSecurity.
     *
     * @param options options
     * @return result
     * @since 0.1.0
     */
    public static AgentCardFetcher fromSecurity(RdcCardFetchOptions options) {
        Objects.requireNonNull(options, "options");
        return new AgentCardFetcher(
                AgentCardMtlsHttpClientFactory.create(options),
                options.getResponseDeadline(),
                AgentCardSignatureVerifier.from(options),
                InternalNetworkPolicy.from(options));
    }

    /**
     * fetch.
     *
     * @param baseUrl baseUrl
     * @param cardPath cardPath
     * @param headers headers
     * @return result
     * @since 0.1.0
     */
    public FetchResult fetch(URI baseUrl, String cardPath, Map<String, String> headers) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String scheme = baseUrl.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return FetchResult.failure("AGENT_CARD_SOURCE_REJECTED", "unsupported scheme: " + scheme);
        }
        Optional<InternalNetworkPolicy.PinnedTarget> pinned = networkPolicy.resolve(baseUrl);
        if (pinned.isEmpty()) {
            return FetchResult.failure("AGENT_CARD_SOURCE_REJECTED",
                    "target host not in allowed CIDR ranges: " + baseUrl.getHost());
        }
        String path = normalizeCardPath(cardPath);
        InternalNetworkPolicy.PinnedTarget target = pinned.get();
        String cardUrl = baseUrl.toString().replaceAll("/$", "") + path;
        try {
            AgentCardHostnamePin.setExpectedHostname(target.originalHost());
            HttpResponse<byte[]> response = httpClient.send(
                    buildPinnedRequest(target.requestUri(path), target, headers),
                    limitingByteArrayHandler());
            return interpretCardResponse(response, cardUrl);
        } catch (InterruptedException ex) {
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return mapIllegalArgument(ex);
        } catch (IOException ex) {
            return mapIoFailure(ex);
        } finally {
            AgentCardHostnamePin.clear();
        }
    }

    private static String normalizeCardPath(String cardPath) {
        String path = cardPath == null || cardPath.isBlank() ? DEFAULT_CARD_PATH : cardPath;
        return path.startsWith("/") ? path : "/" + path;
    }

    private HttpRequest buildPinnedRequest(URI requestUri, InternalNetworkPolicy.PinnedTarget target,
                                           Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(requestUri)
                .timeout(readTimeout)
                .GET();
        boolean hostHeaderSet = false;
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if ("host".equalsIgnoreCase(h.getKey())) {
                    hostHeaderSet = true;
                }
                builder.header(h.getKey(), h.getValue());
            }
        }
        if (!hostHeaderSet) {
            builder.header("Host", target.hostHeaderValue());
        }
        return builder.build();
    }

    private static FetchResult interpretCardResponse(HttpResponse<byte[]> response, String cardUrl) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED",
                    "HTTP " + response.statusCode() + " from " + cardUrl);
        }
        byte[] bodyBytes = response.body();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED", "empty card body from " + cardUrl);
        }
        if (bodyBytes.length > MAX_RESPONSE_BYTES) {
            return FetchResult.failure("AGENT_CARD_INVALID", "card exceeds size limit");
        }
        return FetchResult.success(new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static FetchResult mapIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "invalid card fetch";
        if (message.contains("size limit")) {
            return FetchResult.failure("AGENT_CARD_INVALID", message);
        }
        return FetchResult.failure("AGENT_CARD_FETCH_FAILED", message);
    }

    private static FetchResult mapIoFailure(IOException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof IllegalArgumentException iae
                && iae.getMessage() != null
                && iae.getMessage().contains("size limit")) {
            return FetchResult.failure("AGENT_CARD_INVALID", iae.getMessage());
        }
        return FetchResult.failure("AGENT_CARD_FETCH_FAILED", ex.getMessage());
    }

    private static HttpResponse.BodyHandler<byte[]> limitingByteArrayHandler() {
        return responseInfo -> {
            long contentLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("card exceeds size limit");
            }
            return new LimitingByteArraySubscriber(MAX_RESPONSE_BYTES);
        };
    }

    /**
     * Accumulates response bytes and aborts as soon as {@code maxBytes} is exceeded
     * (covers chunked responses without Content-Length).
     */
    static final class LimitingByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private Flow.Subscription subscription;
        private int received;
        private boolean done;

        LimitingByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done) {
                return;
            }
            for (ByteBuffer buffer : items) {
                int remaining = buffer.remaining();
                if (remaining == 0) {
                    continue;
                }
                if ((long) received + remaining > maxBytes) {
                    abortOversized();
                    return;
                }
                ByteBuffer copy = ByteBuffer.allocate(remaining);
                copy.put(buffer);
                copy.flip();
                buffers.add(copy);
                received += remaining;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (done) {
                return;
            }
            done = true;
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            byte[] body = new byte[received];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                buffer.get(body, offset, remaining);
                offset += remaining;
            }
            result.complete(body);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        private void abortOversized() {
            done = true;
            if (subscription != null) {
                subscription.cancel();
            }
            buffers.clear();
            result.completeExceptionally(new IllegalArgumentException("card exceeds size limit"));
        }
    }

    /**
     * fetchValidated.
     *
     * @param baseUrl baseUrl
     * @param cardPath cardPath
     * @param headers headers
     * @return result
     * @since 0.1.0
     */
    public FetchResult fetchValidated(URI baseUrl, String cardPath, Map<String, String> headers) {
        FetchResult fetched = fetch(baseUrl, cardPath, headers);
        if (!fetched.success()) {
            return fetched;
        }
        AgentCardValidator.ValidationResult validation = AgentCardValidator.validate(fetched.cardJson());
        if (!validation.valid()) {
            return FetchResult.failure(validation.failureCode(), validation.message());
        }
        AgentCardSignatureVerifier.VerificationResult signature =
                signatureVerifier.verify(fetched.cardJson());
        if (!signature.ok()) {
            return FetchResult.failure("AGENT_CARD_SIGNATURE_INVALID", signature.message());
        }
        return FetchResult.success(fetched.cardJson(), validation.capabilityVersion(),
                validation.contractVersion());
    }

    /**
     * FetchResult.
     *
     * @param success success
     * @param cardJson cardJson
     * @param capabilityVersion capabilityVersion
     * @param contractVersion contractVersion
     * @param failureCode failureCode
     * @param message message
     * @return result
     * @since 0.1.0
     */
    public record FetchResult(
            boolean success,
            String cardJson,
            String capabilityVersion,
            String contractVersion,
            String failureCode,
            String message) {
        static FetchResult success(String cardJson) {
            return new FetchResult(true, cardJson, null, null, null, null);
        }

        static FetchResult success(String cardJson, String capabilityVersion, String contractVersion) {
            return new FetchResult(true, cardJson, capabilityVersion, contractVersion, null, null);
        }

        static FetchResult failure(String failureCode, String message) {
            return new FetchResult(false, null, null, null, failureCode, message);
        }
    }
}
