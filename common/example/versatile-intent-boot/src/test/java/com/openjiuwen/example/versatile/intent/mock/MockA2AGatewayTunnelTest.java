/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.mock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link MockA2AGatewayController#tunnel} 直链隧道验证：中间跳卡走哑隧道透传原始 SSE
 * {@code data:} 行；末端业务卡把 serve body 翻译成 versatile {@code {inputs:...}} body
 * 并转发到 versatile mock 路径。
 *
 * @since 0.1.0
 */
class MockA2AGatewayTunnelTest {
    private HttpServer target;
    private MockMvc mockMvc;

    @AfterEach
    void stop() {
        if (target != null) {
            target.stop(0);
        }
    }

    /**
     * 中间跳卡（agent_card_L2_hotel）走哑隧道：原样转发到目标 /v1/query，
     * 透传原始 SSE data: 行，body 不改。
     *
     * @throws Exception 启动本地 HttpServer 或执行 MockMvc 请求时发生异常
     */
    @Test
    void tunnelsRawDataLinesForIntermediateCard() throws Exception {
        target = HttpServer.create(new InetSocketAddress(0), 0);
        target.createContext("/v1/query", exchange -> {
            String sse = "data: {\"custom_rsp_data\":{\"data\":{\"text\":\"ok\"}}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse.getBytes(StandardCharsets.UTF_8));
            }
        });
        target.start();
        int port = target.getAddress().getPort();

        MockA2AGatewayController controller = new MockA2AGatewayController(
                java.util.Map.of("agent_card_L2_hotel", "http://127.0.0.1:" + port));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/a2a/agent_card_L2_hotel")
                        .header("X-Direct-Chain", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"c\",\"stream\":true,\"messages\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data: {\"custom_rsp_data\":{\"data\":{\"text\":\"ok\"}}")));
    }

    /**
     * 末端业务卡（agent_card_biz_hotel_domestic）走 versatile 重写：
     * gateway 把 serve body 翻译成 {@code {inputs:{query,messages}}}，
     * 转发到 {@code /v1/proj/agents/agent_biz/conversations/{cid}}，并透传业务原始 SSE。
     *
     * @throws Exception 启动本地 HttpServer 或执行 MockMvc 请求时发生异常
     */
    @Test
    void rewritesAndForwardsToVersatileMockForTerminalCard() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        target = HttpServer.create(new InetSocketAddress(0), 0);
        target.createContext("/v1/proj/agents/", (HttpExchange exchange) -> {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"custom_rsp_data\":{\"data\":{\"text\":\"酒店预订成功\"}}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse.getBytes(StandardCharsets.UTF_8));
            }
        });
        target.start();
        int port = target.getAddress().getPort();

        MockA2AGatewayController controller = new MockA2AGatewayController(
                java.util.Map.of("agent_card_biz_hotel_domestic", "http://127.0.0.1:" + port));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult mvcResult = mockMvc.perform(post("/a2a/agent_card_biz_hotel_domestic")
                        .header("X-Direct-Chain", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"c5-direct-chain\",\"stream\":true,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"订酒店\"}]}"))
                .andExpect(status().isOk())
                .andReturn();
        // SSE 响应未声明 charset（规范默认 UTF-8），须显式按 UTF-8 解码避免 MockMvc 按 ISO-8859-1 误读
        org.assertj.core.api.Assertions.assertThat(
                        mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("data: {\"custom_rsp_data\":{\"data\":{\"text\":\"酒店预订成功\"}}");

        // 转发到 versatile 路径，cid 取自 conversation_id
        org.assertj.core.api.Assertions.assertThat(capturedPath.get())
                .isEqualTo("/v1/proj/agents/agent_biz/conversations/c5-direct-chain");
        // body 已重写为 versatile {inputs:{query,messages}} 形态
        org.assertj.core.api.Assertions.assertThat(capturedBody.get())
                .contains("\"inputs\"")
                .contains("\"query\":\"订酒店\"");
    }
}
