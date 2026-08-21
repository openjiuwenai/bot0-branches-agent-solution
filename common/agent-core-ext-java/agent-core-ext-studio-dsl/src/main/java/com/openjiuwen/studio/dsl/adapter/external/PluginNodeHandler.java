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
import java.util.Map;
import java.util.Set;

/** jiuwen.plugin — apiId→ToolRegistry, else url HTTP; consumes MediaPart. */
public final class PluginNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.plugin";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.api", "jiuwen.flowApi");
    }

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

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            List<MediaPart> media = MediaSupport.mediaOf(inputs);
            Map<String, Object> uf = MediaSupport.withConsumableMedia(userFieldsOf(inputs), media);
            Map<String, Object> configs = node.configs();
            if (configs.containsKey("mockResponse")) {
                Object mock = configs.get("mockResponse");
                if (mock instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> uf.put(String.valueOf(k), v));
                } else {
                    uf.put("raw_output", mock);
                }
                uf.put("mediaConsumed", !media.isEmpty());
                return NodePayload.userFields(uf).withMediaPassthrough(media);
            }

            Object apiId = configs.get("apiId");
            if (apiId != null && toolRegistry != null) {
                Tool tool = toolRegistry
                        .find(String.valueOf(apiId))
                        .orElseThrow(() -> new NodeExecutionException(
                                node.id(),
                                "jiuwen.plugin",
                                NodeCauseCode.NODE_CONFIG_INVALID,
                                "apiId not found in ToolRegistry: " + apiId));
                Map<String, Object> args = new LinkedHashMap<>(uf);
                Object result = tool.invoke(args);
                if (result instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> uf.put(String.valueOf(k), v));
                } else {
                    uf.put("raw_output", result);
                }
                uf.put("mediaConsumed", !media.isEmpty());
                return NodePayload.userFields(uf).withMediaPassthrough(media);
            }

            String url = str(configs.getOrDefault("url", configs.get("endpoint")));
            if (url == null || url.isBlank()) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.plugin",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "url, apiId+ToolRegistry, or mockResponse required");
            }
            url = TemplateRenderer.render(url, uf);
            String method = str(configs.getOrDefault("method", "GET"));
            if (method == null) {
                method = "GET";
            }
            long timeoutMs = 10_000L;
            Object t = configs.get("timeoutMs");
            if (t instanceof Number n) {
                timeoutMs = n.longValue();
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs));
            String body = str(configs.get("body"));
            if (body != null) {
                body = TemplateRenderer.render(body, uf);
            } else if (!media.isEmpty()) {
                body = "{\"mediaCount\":" + media.size() + "}";
            }
            switch (method.toUpperCase()) {
                case "POST" -> rb.POST(
                        HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
                case "PUT" -> rb.PUT(
                        HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
                case "DELETE" -> rb.DELETE();
                default -> rb.GET();
            }
            HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            uf.put("statusCode", resp.statusCode());
            uf.put("raw_output", resp.body());
            uf.put("body", resp.body());
            uf.put("mediaConsumed", !media.isEmpty());
            return NodePayload.userFields(uf).withMediaPassthrough(media);
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }
}
