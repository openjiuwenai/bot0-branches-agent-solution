package com.openjiuwen.rdc.card;

import com.openjiuwen.rdc.security.AgentCardFetchSecurityProperties;
import com.openjiuwen.rdc.security.InternalNetworkPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Fetches {@code /.well-known/agent-card.json} with security boundaries (0711 §5.1.3).
 */
public final class AgentCardFetcher {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    public static final String DEFAULT_CARD_PATH = "/.well-known/agent-card.json";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final AgentCardSignatureVerifier signatureVerifier;
    private final InternalNetworkPolicy networkPolicy;

    public AgentCardFetcher() {
        this(AgentCardMtlsHttpClientFactory.create(new AgentCardFetchSecurityProperties()),
                DEFAULT_READ_TIMEOUT,
                AgentCardSignatureVerifier.disabled(),
                InternalNetworkPolicy.permissive());
    }

    public static AgentCardFetcher fromSecurity(AgentCardFetchSecurityProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return new AgentCardFetcher(
                AgentCardMtlsHttpClientFactory.create(properties),
                properties.getReadTimeout(),
                AgentCardSignatureVerifier.from(properties),
                InternalNetworkPolicy.from(properties));
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

    public FetchResult fetch(URI baseUrl, String cardPath, Map<String, String> headers) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        String scheme = baseUrl.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return FetchResult.failure("AGENT_CARD_SOURCE_REJECTED", "unsupported scheme: " + scheme);
        }
        if (!networkPolicy.isAllowed(baseUrl)) {
            return FetchResult.failure("AGENT_CARD_SOURCE_REJECTED",
                    "target host not in allowed CIDR ranges: " + baseUrl.getHost());
        }
        String path = cardPath == null || cardPath.isBlank() ? DEFAULT_CARD_PATH : cardPath;
        String cardUrl = baseUrl.toString().replaceAll("/$", "") + path;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(cardUrl))
                    .timeout(readTimeout)
                    .GET();
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    builder.header(h.getKey(), h.getValue());
                }
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return FetchResult.failure("AGENT_CARD_FETCH_FAILED",
                        "HTTP " + response.statusCode() + " from " + cardUrl);
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return FetchResult.failure("AGENT_CARD_FETCH_FAILED", "empty card body from " + cardUrl);
            }
            if (body.length() > MAX_RESPONSE_BYTES) {
                return FetchResult.failure("AGENT_CARD_INVALID", "card exceeds size limit");
            }
            return FetchResult.success(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED", ex.getMessage());
        } catch (IOException ex) {
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED", ex.getMessage());
        } catch (RuntimeException ex) {
            return FetchResult.failure("AGENT_CARD_FETCH_FAILED", ex.getMessage());
        }
    }

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

    public record FetchResult(boolean success, String cardJson, String capabilityVersion,
                              String contractVersion, String failureCode, String message) {
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
