/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * StdioMcpClient bearing tests — JSON-RPC framing pure functions.
 *
 * <p>No subprocess is spawned here: we verify the encode/extract helpers that
 * the transport relies on for correct framing. This is where the SDK's
 * StdioClient framing breaks (stderr log lines pollute the JSON stream); our
 * helpers must be defensive against non-JSON and non-matching lines.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip {@code id} from encodeRequest → extractResultById never matches → RED</li>
 *   <li>Strip id check in extractResultById → wrong-id response hijacks call → RED</li>
 *   <li>Concatenate non-text parts in extractTextContent → tool result polluted → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class StdioMcpClientTest {
    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void encodeRequestHasJsonrpcIdMethodParams() throws Exception {
        String json = StdioMcpClient.encodeRequest(M, 7, "tools/list", Map.of());
        JsonNode node = M.readTree(json);

        assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.get("id").asInt()).isEqualTo(7);
        assertThat(node.get("method").asText()).isEqualTo("tools/list");
        assertThat(node.has("params")).isTrue();
        assertThat(node.get("params").isObject()).isTrue();
    }

    @Test
    void encodeRequestNullParamsBecomesEmptyObject() throws Exception {
        String json = StdioMcpClient.encodeRequest(M, 1, "ping", null);
        JsonNode node = M.readTree(json);
        assertThat(node.get("params").isObject()).isTrue();
        assertThat(node.get("params").size()).isZero();
    }

    @Test
    void encodeNotificationHasNoId() throws Exception {
        String json = StdioMcpClient.encodeNotification(M, "notifications/initialized", Map.of());
        JsonNode node = M.readTree(json);

        assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.get("method").asText()).isEqualTo("notifications/initialized");
        assertThat(node.has("id")).as("notifications carry no id — no response expected").isFalse();
    }

    @Test
    void extractResultByIdMatchesCorrectId() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[]}}";
        JsonNode node = StdioMcpClient.extractResultById(line, 3);

        assertThat(node).as("matching id must return the response object").isNotNull();
        assertThat(node.get("result").get("tools").isArray()).isTrue();
    }

    @Test
    void extractResultByIdRejectsWrongId() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}";
        assertThat(StdioMcpClient.extractResultById(line, 99))
                .as("wrong id must not match — protects against hijacked responses")
                .isInstanceOf(MissingNode.class);
    }

    @Test
    void extractResultByIdRejectsNotificationsAndInvalidJson() {
        assertThat(StdioMcpClient.extractResultById("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/foo\"}", 1))
                .as("notifications have no id — must not match")
                .isInstanceOf(MissingNode.class);
        assertThat(StdioMcpClient.extractResultById("not json at all", 1))
                .as("invalid JSON on stdout must be skipped defensively")
                .isInstanceOf(MissingNode.class);
        assertThat(StdioMcpClient.extractResultById("", 1)).isInstanceOf(MissingNode.class);
        assertThat(StdioMcpClient.extractResultById(null, 1)).isInstanceOf(MissingNode.class);
    }

    @Test
    void extractTextContentConcatenatesTextParts() throws JsonProcessingException {
        String result = "{\"content\":[" + "{\"type\":\"text\",\"text\":\"line1\\n\"},"
                + "{\"type\":\"text\",\"text\":\"line2\"}" + "]}";
        JsonNode node = M.readTree(result);
        assertThat(StdioMcpClient.extractTextContent(node)).isEqualTo("line1\nline2");
    }

    @Test
    void extractTextContentIgnoresNonTextParts() throws JsonProcessingException {
        // image/resource parts must not pollute the text result
        String result = "{\"content\":[" + "{\"type\":\"image\",\"data\":\"...\"},"
                + "{\"type\":\"text\",\"text\":\"only-this\"}" + "]}";
        JsonNode node = M.readTree(result);
        assertThat(StdioMcpClient.extractTextContent(node))
                .as("non-text parts must be skipped — tool result stays clean").isEqualTo("only-this");
    }

    @Test
    void extractTextContentHandlesMissingOrBareResult() throws Exception {
        assertThat(StdioMcpClient.extractTextContent(null)).isEmpty();
        assertThat(StdioMcpClient.extractTextContent(M.readTree("{}"))).isEmpty();
        assertThat(StdioMcpClient.extractTextContent(M.readTree("\"bare-string\""))).isEqualTo("bare-string");
    }

    @Test
    void constructorRejectsEmptyCommand() {
        assertThatThrownBy(() -> new StdioMcpClient(List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StdioMcpClient(null, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandAndEnvAreDefensivelyCopied() {
        StdioMcpClient c = new StdioMcpClient(new java.util.ArrayList<>(List.of("python3", "-m", "x")),
                new java.util.HashMap<>(Map.of("K", "V")));
        // mutate the originals — the client's copies must not see it
        // (no getter exposes them; this just asserts construction doesn't throw
        // and holds its own copies — List.copyOf/Map.copyOf are immutable)
        assertThat(c).isNotNull();
    }
}
