/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The {@link Protocol#REST REST-mode} {@link PlanAgentClient}: POST a {@link RestRequest} to
 * {@code <base>/v1/query?<workspace_id>&<type>} and pipe the SSE reply into the client's output
 * stream. Active only when {@code versatile-orchestration.gateway.plan-agent-protocol=rest}.
 *
 * <p><b>SSE handling — verbatim passthrough.</b> Unlike {@link A2aStreamingClient} (which consumes
 * JSON-RPC status frames for resume bookkeeping and normalizes artifact frames into EDPA
 * {@code {event,data}}), this client forwards every {@code data:} frame untouched. The REST
 * endpoint is served by the runtime's {@code QueryMvcController}, which streams the runtime's
 * already-normalized internal events; the frames are therefore expected to be EDPA-shaped
 * {@code {"event":...,"data":{...}}} on the wire — no taskId state, no envelope unwrapping, and no
 * {@link ResumeStateStore} (REST resume is routed by {@code conversation_id}).
 *
 * <p>If a real run ever shows non-{@code {event,data}} frames here, the fix is localized to this
 * class: slot in a normalizer analogous to {@code A2aArtifactNormalizer} on the parsed payload
 * before {@link #forwardFrame}.
 *
 * @since 2026-07-08
 */
@Component
@ConditionalOnProperty(prefix = "versatile-orchestration.gateway", name = "plan-agent-protocol",
        havingValue = "rest")
public class RestPlanAgentClient implements PlanAgentClient {
    private static final Logger LOG = LoggerFactory.getLogger(RestPlanAgentClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final GatewayProperties properties;

    public RestPlanAgentClient(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void forward(EdpaRequest request, Map<String, Object> headers, Map<String, Object> queryParams,
                        OutputStream clientOutput) throws IOException, InterruptedException {
        RestRequest rest = RestRequest.from(request, properties);
        String endpoint = restEndpoint(queryParams);
        String bodyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rest.body());
        LOG.info(">>> POST {} - outbound REST request body:\n{}", endpoint, bodyJson);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(300));
        // Whitelisted inbound headers travel as HTTP headers (the REST contract mirrors the demo,
        // which sets stream/x-invoke-mode/x-language as request headers).
        EdpaRequest.forwardHeaders(headers, properties.forwardHeaderWhitelist())
                .forEach(req::header);
        req.POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));

        HttpResponse<java.io.InputStream> resp = http.send(req.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        LOG.info("<<< {} responded HTTP {}", endpoint, status);
        if (status != 200) {
            try {
                resp.body().close();
            } catch (IOException ignored) {} /* best-effort */
            throw new IOException("plan-agent returned status " + status);
        }

        Writer out = new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8);
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                LOG.info("<<< SSE frame from plan-agent: {}", payload);
                forwardFrame(out, payload);
            }
            out.flush();
        }
    }

    /**
     * The plan-agent's full REST endpoint: the configured base URL plus {@code /v1/query} and the
     * inbound URL query params ({@code workspace_id}, {@code type}) appended as a URL query string
     * (the runtime surfaces them as {@code ServeRequest.metadata.query}, which the versatile
     * handler forwards to the downstream mock). A trailing slash on the base is tolerated.
     *
     * @param queryParams inbound URL query params appended as a URL query string (may be null/empty)
     * @return the full REST endpoint URL for the plan-agent
     */
    private String restEndpoint(Map<String, Object> queryParams) {
        String base = properties.planAgentBaseUrl();
        String path = base.endsWith("/") ? base + "v1/query" : base + "/v1/query";
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        StringBuilder qs = new StringBuilder();
        queryParams.forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }
            if (qs.length() > 0) {
                qs.append('&');
            }
            qs.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8));
        });
        return qs.length() == 0 ? path : path + "?" + qs;
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
}
