package com.openjiuwen.example.versatile.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

public final class VersatileA2AClientMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private VersatileA2AClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String endpointUrl = System.getenv().getOrDefault("A2A_ENDPOINT_URL", "http://127.0.0.1:18080/a2a/");

        String requestJson1 = """
                {
                  "jsonrpc": "2.0",
                  "id": "versatile-a2a-demo-1",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "versatile-a2a-1",
                      "parts": [
                        {
                          "text": "{\\"query\\":\\"先查询尾号为4241的银行卡余额，再转账5元给李四\\",\\"intent\\":\\"查询账户余额\\"}"
                        }
                      ]
                    },
                    "metadata": {
                      "body": {
                        "agent_id": "main_planner",
                        "input": {
                          "query": "xxx",
                          "intent": "xxx",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "xxx",
                            "intent": "xxx",
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
                        "x-debug-trace": "trace-from-example"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;

        String requestJson2 = """
                {
                  "jsonrpc": "2.0",
                  "id": "versatile-a2a-demo-2",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "versatile-a2a-1",
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
                          "query": "xxx",
                          "intent": "xxx",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "xxx",
                            "intent": "xxx",
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
                        "x-debug-trace": "trace-from-example"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;

        String requestJson3 = """
                {
                  "jsonrpc": "2.0",
                  "id": "versatile-a2a-demo-3",
                  "method": "SendStreamingMessage",
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "contextId": "versatile-a2a-1",
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
                          "query": "xxx",
                          "intent": "xxx",
                          "wap_userName": "张三"
                        },
                        "conversation_id": "test-session-001",
                        "timeout": "300",
                        "role_id": "1",
                        "role_name": "手机银行",
                        "stream": true,
                        "custom_data": {
                          "inputs": {
                            "query": "xxx",
                            "intent": "xxx",
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
                        "x-debug-trace": "trace-from-example"
                      },
                      "query": {
                        "workspace_id": "11",
                        "type": "controller"
                      }
                    }
                  }
                }
                """;

        sendRequest(endpointUrl, requestJson1);
        sendRequest(endpointUrl, requestJson2);
        sendRequest(endpointUrl, requestJson3);
    }

    private static void sendRequest(String endpointUrl, String requestJson) throws Exception {
        Map<String, Object> root = MAPPER.readValue(requestJson, MAP_TYPE);
        Map<String, Object> params = mapValue(root.get("params"));
        Map<String, Object> metadata = mapValue(params.get("metadata"));
        Map<String, Object> headers = new LinkedHashMap<>(mapValue(metadata.get("headers")));

        System.out.println("POST " + endpointUrl);
        System.out.println("Request body:");
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));

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

        System.out.println("HTTP " + response.statusCode());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        System.out.println();
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
