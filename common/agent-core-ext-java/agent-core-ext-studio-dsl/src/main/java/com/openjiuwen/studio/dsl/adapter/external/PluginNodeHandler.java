/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.spi.ToolRegistry;
import com.openjiuwen.studio.dsl.util.MediaSupport;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.plugin — apiId to ToolRegistry, else url HTTP; consumes MediaPart.
 *
 * @since 2026-08-17
 */
public final class PluginNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return canonical type
     */
    @Override
    public String canonicalType() {
        return "jiuwen.plugin";
    }

    /**
     * aliases.
     *
     * @return aliases
     */
    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.api", "jiuwen.flowApi");
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return executable
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new PluginExecutable(node, ctx.toolRegistry());
    }

    static final class PluginExecutable extends AbstractStudioNode {
        private final ToolRegistry toolRegistry;

        PluginExecutable(AssembledNode node, ToolRegistry toolRegistry) {
            super(node);
            this.toolRegistry = toolRegistry;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return payload
         * @throws Exception when the call fails
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            List<MediaPart> media = MediaSupport.mediaOf(inputs);
            Map<String, Object> uf = MediaSupport.withConsumableMedia(userFieldsOf(inputs), media);
            Map<String, Object> configs = node.configs();
            if (configs.containsKey("mockResponse")) {
                return applyMock(uf, media, configs.get("mockResponse"));
            }
            Object apiId = configs.get("apiId");
            if (apiId != null && toolRegistry != null) {
                return invokeTool(uf, media, apiId);
            }
            return invokeHttp(uf, media, configs);
        }

        private NodePayload applyMock(Map<String, Object> uf, List<MediaPart> media, Object mock) {
            if (mock instanceof Map<?, ?> m) {
                m.forEach((k, v) -> uf.put(String.valueOf(k), v));
            } else {
                uf.put("raw_output", mock);
            }
            uf.put("mediaConsumed", !media.isEmpty());
            return NodePayload.userFields(uf).withMediaPassthrough(media);
        }

        private NodePayload invokeTool(Map<String, Object> uf, List<MediaPart> media, Object apiId)
                throws Exception {
            Tool tool = toolRegistry
                    .find(String.valueOf(apiId))
                    .orElseThrow(() -> new NodeExecutionException(
                            node.id(),
                            "jiuwen.plugin",
                            NodeCauseCode.NODE_CONFIG_INVALID,
                            "apiId not found in ToolRegistry: " + apiId));
            Object result = tool.invoke(new LinkedHashMap<>(uf));
            mergeResult(uf, result);
            uf.put("mediaConsumed", !media.isEmpty());
            return NodePayload.userFields(uf).withMediaPassthrough(media);
        }

        private NodePayload invokeHttp(Map<String, Object> uf, List<MediaPart> media, Map<String, Object> configs)
                throws Exception {
            String url = str(configs.getOrDefault("url", configs.get("endpoint")));
            if (url.isBlank()) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.plugin",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "url, apiId+ToolRegistry, or mockResponse required");
            }
            url = TemplateRenderer.render(url, uf);
            String method = str(configs.getOrDefault("method", "GET"));
            if (method.isBlank()) {
                method = "GET";
            }
            long timeoutMs = 10_000L;
            Object t = configs.get("timeoutMs");
            if (t instanceof Number n) {
                timeoutMs = n.longValue();
            }
            HttpResponse<String> resp = send(url, method, bodyOf(configs, uf, media), timeoutMs);
            uf.put("statusCode", resp.statusCode());
            uf.put("raw_output", resp.body());
            uf.put("body", resp.body());
            uf.put("mediaConsumed", !media.isEmpty());
            return NodePayload.userFields(uf).withMediaPassthrough(media);
        }

        private static String bodyOf(Map<String, Object> configs, Map<String, Object> uf, List<MediaPart> media) {
            String body = str(configs.get("body"));
            if (!body.isEmpty()) {
                return TemplateRenderer.render(body, uf);
            } else if (!media.isEmpty()) {
                return "{\"mediaCount\":" + media.size() + "}";
            } else {
                return "";
            }
        }

        private static HttpResponse<String> send(String url, String method, String body, long timeoutMs)
                throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs));
            applyMethod(rb, method, body);
            return client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private static void applyMethod(HttpRequest.Builder rb, String method, String body) {
            String payload = body == null ? "" : body;
            switch (method.toUpperCase(Locale.ROOT)) {
                case "POST" -> rb.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                case "PUT" -> rb.PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                case "DELETE" -> rb.DELETE();
                default -> rb.GET();
            }
        }

        private static void mergeResult(Map<String, Object> uf, Object result) {
            if (result instanceof Map<?, ?> m) {
                m.forEach((k, v) -> uf.put(String.valueOf(k), v));
            } else {
                uf.put("raw_output", result);
            }
        }

        private static String str(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
