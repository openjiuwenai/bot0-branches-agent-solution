package com.openjiuwen.example.versatile.intent.mock;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.net.InetSocketAddress;
import java.io.OutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MockA2AGatewayTunnelTest {
    private HttpServer target;
    private MockMvc mockMvc;

    @AfterEach
    void stop() { if (target != null) target.stop(0); }

    @Test
    void tunnelsRawDataLinesForDirectChainHeader() throws Exception {
        // 目标 /v1/query 返回原始 SSE data: 行
        target = HttpServer.create(new InetSocketAddress(0), 0);
        target.createContext("/v1/query", exchange -> {
            String sse = "data: {\"custom_rsp_data\":{\"data\":{\"text\":\"ok\"}}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            os.write(sse.getBytes());
            os.close();
        });
        target.start();
        int port = target.getAddress().getPort();

        // 用子类覆盖 ROUTING 指向本地 target（ROUTING 是 package-private static final，
        // 测试无法改；故用反射或改 ROUTING 为可注入。实现步骤会把 ROUTING 改为可被构造器覆盖。）
        MockA2AGatewayController controller = new MockA2AGatewayController(
                java.util.Map.of("agent_card_biz_hotel_domestic", "http://127.0.0.1:" + port));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/a2a/agent_card_biz_hotel_domestic")
                        .header("X-Direct-Chain", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"c\",\"stream\":true,\"messages\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data: {\"custom_rsp_data\":{\"data\":{\"text\":\"ok\"}}")));
    }
}
