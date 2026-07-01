/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agent_a.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command-line client that exercises the AgentCore extension A2A demo endpoint.
 *
 * @since 2026-06-30
 */
public final class AgentCoreExtA2AClientMain {
    private static final Logger log = LoggerFactory.getLogger(AgentCoreExtA2AClientMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private AgentCoreExtA2AClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String requestJson1 = """
                {
                  "jsonrpc": "2.0",
                  "id": "agentcore-ext-remote-a2a-tool-demo-1",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "agentcore-ext-remote-a2a-tool-demo-1",
                      "parts": [
                        {
                          "text": "{\\"query\\":\\"请使用可处理银行业务流程的远端能力处理：先查询尾号为4241的银行卡余额，再转账5元给李四\\",\\"intent\\":\\"查询账户余额\\"}"
                        }
                      ]
                    },
                    "metadata": {
                      "body": {
                        "agent_id": "main_planner",
                        "input": {
                          "query": "metadata-body-fixed-query",
                          "intent": "metadata-body-fixed-intent",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "custom-data-fixed-query",
                            "intent": "custom-data-fixed-intent",
                            "wap_userName": "张三"
                          },
                          "memory_inputs": {},
                          "globals": {},
                          "plugin_configs": [],
                          "long_term_memory": {
                            "enable_retrieve": true,
                            "enable_extract": true
                          }
                        }
                      },
                      "headers": {
                        "stream": "true",
                        "x-invoke-mode": "DEBUG",
                        "x-language": "zh-cn",
                        "x-debug-trace": "trace-from-agentcore-ext-demo"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;
        String endpointUrl = System.getenv().getOrDefault("A2A_ENDPOINT_URL", "http://127.0.0.1:18090/a2a/");
        sendRequest(endpointUrl, requestJson1);

        String requestJson2 = """
                {
                  "jsonrpc": "2.0",
                  "id": "agentcore-ext-remote-a2a-tool-demo-2",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "agentcore-ext-remote-a2a-tool-demo-1",
                      "parts": [
                        {
                          "text": "{\\"query\\":\\"[{\\\\\\"cardNum\\\\\\":\\\\\\"6222021816044054241\\\\\\",\\\\\\"regAcctType\\\\\\":\\\\\\"011\\\\\\",\\\\\\"cardAlias\\\\\\":\\\\\\"\\\\\\"}]\\",\\"intent\\":\\"LATEST\\"}"
                        }
                      ]
                    },
                    "metadata": {
                      "body": {
                        "agent_id": "main_planner",
                        "input": {
                          "query": "metadata-body-fixed-query",
                          "intent": "metadata-body-fixed-intent",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "custom-data-fixed-query",
                            "intent": "custom-data-fixed-intent",
                            "wap_userName": "张三"
                          },
                          "memory_inputs": {},
                          "globals": {},
                          "plugin_configs": [],
                          "long_term_memory": {
                            "enable_retrieve": true,
                            "enable_extract": true
                          }
                        }
                      },
                      "headers": {
                        "stream": "true",
                        "x-invoke-mode": "DEBUG",
                        "x-language": "zh-cn",
                        "x-debug-trace": "trace-from-agentcore-ext-demo"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;
        sendRequest(endpointUrl, requestJson2);

        String requestJson3 = """
                {
                  "jsonrpc": "2.0",
                  "id": "agentcore-ext-remote-a2a-tool-demo-3",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "agentcore-ext-remote-a2a-tool-demo-1",
                      "parts": [
                        {
                          "text": "{\\"query\\":\\"{\\\\\\"bankCardBalanceList\\\\\\":[{\\\\\\"bankCardNumber\\\\\\":\\\\\\"6222021816044054241\\\\\\",\\\\\\"mediumStatus\\\\\\":\\\\\\"0\\\\\\",\\\\\\"currencyBalanceList\\\\\\":[{\\\\\\"currencyCode\\\\\\":\\\\\\"001\\\\\\",\\\\\\"currencyName\\\\\\":\\\\\\"人民币可用余额\\\\\\",\\\\\\"balance\\\\\\":\\\\\\"1500.92\\\\\\"}]}],\\\\\\"responseData\\\\\\":[{\\\\\\"answer\\\\\\":\\\\\\"已为您查询账户余额\\\\\\",\\\\\\"readme\\\\\\":\\\\\\"已为您查询账户余额\\\\\\",\\\\\\"pageData\\\\\\":\\\\\\"\\\\\\",\\\\\\"type\\\\\\":\\\\\\"1\\\\\\"}]}\\",\\"intent\\":\\"LATEST\\"}"
                        }
                      ]
                    },
                    "metadata": {
                      "body": {
                        "agent_id": "main_planner",
                        "input": {
                          "query": "metadata-body-fixed-query",
                          "intent": "metadata-body-fixed-intent",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "custom-data-fixed-query",
                            "intent": "custom-data-fixed-intent",
                            "wap_userName": "张三"
                          },
                          "memory_inputs": {},
                          "globals": {},
                          "plugin_configs": [],
                          "long_term_memory": {
                            "enable_retrieve": true,
                            "enable_extract": true
                          }
                        }
                      },
                      "headers": {
                        "stream": "true",
                        "x-invoke-mode": "DEBUG",
                        "x-language": "zh-cn",
                        "x-debug-trace": "trace-from-agentcore-ext-demo"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;
        sendRequest(endpointUrl, requestJson3);
    }

    private static void sendRequest(String endpointUrl, String requestJson) throws Exception {
        Map<String, Object> root = MAPPER.readValue(requestJson, MAP_TYPE);
        Map<String, Object> params = mapValue(root.get("params"));
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        Map<String, Object> headers = new LinkedHashMap<>(mapValue(metadata.get("headers")));

        log.info("POST {}", endpointUrl);
        log.info("Request body:");
        log.info("{}", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(600))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");

        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getValue() != null) {
                requestBuilder.header(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        log.info("HTTP {}", response.statusCode());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("{}", line);
            }
        }
        log.info("");
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }
}
