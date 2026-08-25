/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 智谱 web_search 工具——真实搜索引擎后端。
 *
 * <p>模型可见文本面（description/结果模板/错误文案）全部外置
 * {@code src/main/resources/prompts/*.txt} 经 {@link PromptTemplates} 渲染
 * （Phase 1 四代进化终审设计：契约常驻 + 失败现场弹药 + 最便宜结构锚）。
 *
 * <p>与 {@link WikipediaSearchTool}（opensearch 标题前缀匹配）互补：
 * 本工具走智谱开放平台 search_prime 引擎。bigmodel.cn 国内直连，无需代理。
 *
 * @since 2026-08
 */
public class ZhipuWebSearchTool extends Tool {

    public static final String TOOL_NAME = "web_search";

    private static final String ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";

    /** 结果条数上限——description 的 {maxResults} 与 format 截断同源单点（防漂移）。 */
    static final int MAX_RESULTS = 3;

    /**
     * excerpt 内匹配 harness 结果形状的行加引用前缀中和
     * （锚行+引用行的承重前提——防网页内容伪造结果条目/终止符）。
     */
    private static final Pattern[] HARNESS_SHAPE_LINES = {
            Pattern.compile("^\\s*\\[\\d+\\]"),
            Pattern.compile("^— end of"),
            Pattern.compile("^Cite results"),
            Pattern.compile("^Results for web search")
    };

    private final HttpClient httpClient;

    private final String apiKey;

    private final String searchEngine;

    private final PromptTemplates templates;

    /**
     * 构造工具（engine 缺省 search_prime）。
     *
     * @param apiKey bigmodel API key
     */
    public ZhipuWebSearchTool(String apiKey) {
        this(apiKey, System.getenv().getOrDefault("ZHIPU_SEARCH_ENGINE", "search_prime"));
    }

    /**
     * 构造工具。
     *
     * @param apiKey       bigmodel API key
     * @param searchEngine 搜索引擎（search_prime / search_pro / search_std）
     */
    public ZhipuWebSearchTool(String apiKey, String searchEngine) {
        this(apiKey, searchEngine, new PromptTemplates());
    }

    /**
     * 构造工具（模板与时钟可注入——测试用固定钟）。
     *
     * @param apiKey       bigmodel API key
     * @param searchEngine 搜索引擎
     * @param templates    模板渲染器
     */
    ZhipuWebSearchTool(String apiKey, String searchEngine, PromptTemplates templates) {
        super(ToolCard.builder()
                .id(TOOL_NAME)
                .name(TOOL_NAME)
                .description(renderDescription(templates))
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "The search query")),
                        "required", List.of("query")))
                .build());
        this.apiKey = apiKey;
        this.searchEngine = searchEngine;
        this.templates = templates;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * D1 工具 description——外置模板渲染（运行期年月注入，单源化）。
     *
     * @param templates 模板渲染器
     * @return 渲染后 description
     */
    private static String renderDescription(PromptTemplates templates) {
        return templates.render("web-search-description.txt",
                Map.of("maxResults", String.valueOf(MAX_RESULTS),
                        "currentMonthYear", templates.currentMonthYear()));
    }

    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
        String query = String.valueOf(args.getOrDefault("query", ""));
        if (query.isBlank()) {
            return Map.of("error", "query is required");
        }
        try {
            return Map.of("query", query, "result", search(query));
        } catch (InterruptedException e) {
            // 仅真中断才恢复标志（无条件 interrupt 会毒化宿主线程）
            Thread.currentThread().interrupt();
            return Map.of("error", "search interrupted");
        } catch (Exception e) {
            return Map.of("error", renderTransportError(e));
        }
    }

    @Override
    public java.util.Iterator<Object> stream(Map<String, Object> args,
            Map<String, Object> kwargs) throws Exception {
        return List.of((Object) invoke(args, kwargs)).iterator();
    }

    private String search(String query) throws Exception {
        String body = "{\"search_query\":\"" + escape(query) + "\""
                + ",\"location\":\"us\""
                + ",\"search_engine\":\"" + searchEngine + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return httpErrorText(response.statusCode());
        }
        return format(query, response.body());
    }

    /**
     * D3 三键通道：按真实 HTTP 码路由（429/4xx/5xx 分药，非一张药方）。
     *
     * <p>401/403（认证/授权失败）归 backend 通道——认证错不是查询的错，
     * 不教模型改写查询（探针实证：智谱错误体全部带正确非 200 码）。
     *
     * @param code HTTP 状态码
     * @return 对应通道文案
     */
    String httpErrorText(int code) {
        if (code == 429) {
            return templates.render("web-search-error-rate-limited.txt",
                    Map.of("code", String.valueOf(code)));
        }
        if ((code == 401 || code == 403) || code >= 500) {
            return templates.render("web-search-error-backend.txt",
                    Map.of("code", String.valueOf(code)));
        }
        if (code >= 400 && code < 500) {
            return templates.render("web-search-error-rejected.txt", Map.of());
        }
        return templates.render("web-search-error-backend.txt",
                Map.of("code", String.valueOf(code)));
    }

    /**
     * 传输层异常文案（超时签名分流，其余 transport 变体）。
     *
     * @param e 异常
     * @return D3 文案
     */
    private String renderTransportError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("timed out") || e instanceof java.net.http.HttpTimeoutException) {
            return templates.render("web-search-error-timeout.txt", Map.of());
        }
        return templates.render("web-search-error-backend.txt",
                Map.of("code", "transport"));
    }

    /**
     * D2 结果装配：header → 条目（[n] title (url) + excerpt 形状中和）→
     * [截断行] → [单源行] → 锚定终止符（计数+日期，恒为最后）。
     *
     * @param query 原查询（header 回显）
     * @param json  web_search JSON 响应
     * @return 渲染后文本；无结果走 empty 通道文案
     */
    String format(String query, String json) {
        int arr = locateResultArray(json);
        List<String[]> shown = new ArrayList<>(); // {title, link, content}
        int total = 0;
        if (arr >= 0) {
            int i = JsonStringScan.skipWs(json, arr + 1);
            while (i < json.length() && shown.size() < MAX_RESULTS) {
                char c = json.charAt(i);
                if (c == ']') {
                    break;
                }
                if (c == '{') {
                    int end = JsonStringScan.objectEnd(json, i);
                    if (end < 0) {
                        break;
                    }
                    String obj = json.substring(i, end + 1);
                    String title = JsonStringScan.stringValueOf(obj, "title");
                    String content = JsonStringScan.stringValueOf(obj, "content");
                    if (title != null && content != null) {
                        total++;
                        if (shown.size() < MAX_RESULTS) {
                            shown.add(new String[] {title,
                                    JsonStringScan.stringValueOf(obj, "link"), content});
                        }
                    }
                    i = JsonStringScan.skipWs(json, end + 1);
                    if (i < json.length() && json.charAt(i) == ',') {
                        i = JsonStringScan.skipWs(json, i + 1);
                    }
                } else {
                    i++;
                }
            }
            // 数组里剩余对象也计入 total（截断行的 {total} 承重事实）
            i = countRemaining(json, i, total);
            total = i;
        }
        if (shown.isEmpty()) {
            return templates.render("web-search-error-empty.txt", Map.of());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(templates.render("web-search-result-header.txt",
                Map.of("query", query)));
        int n = 0;
        for (String[] item : shown) {
            n++;
            String urlPart = item[1] != null ? " (" + item[1] + ")" : "";
            sb.append("\n").append(templates.render("web-search-result-entry.txt",
                    Map.of("n", String.valueOf(n), "title", item[0],
                            "urlPart", urlPart, "excerpt", neutralize(item[2]))));
        }
        if (total > shown.size()) {
            sb.append("\n").append(templates.render("web-search-truncated-note.txt",
                    Map.of("shown", String.valueOf(shown.size()),
                            "total", String.valueOf(total))));
        }
        if (shown.size() == 1) {
            sb.append("\n").append(templates.render("web-search-single-source-note.txt",
                    Map.of()));
        }
        sb.append("\n").append(templates.render("web-search-result-tail.txt",
                Map.of("n", String.valueOf(shown.size()),
                        "currentDate", templates.currentDate())));
        return sb.toString().trim();
    }

    /**
     * 数组尾部继续计数（已达条数上限后剩余的合法对象数）。
     *
     * @param json  响应文本
     * @param from  扫描位置
     * @param count 已计数
     * @return 总数
     */
    private static int countRemaining(String json, int from, int count) {
        int i = from;
        int total = count;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ']') {
                break;
            }
            if (c == '{') {
                int end = JsonStringScan.objectEnd(json, i);
                if (end < 0) {
                    break;
                }
                String obj = json.substring(i, end + 1);
                if (JsonStringScan.stringValueOf(obj, "title") != null
                        && JsonStringScan.stringValueOf(obj, "content") != null) {
                    total++;
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return total;
    }

    /**
     * excerpt 中匹配 harness 结果形状的行加引用前缀（注入中和——网页内容
     * 不得伪造结果条目/引用行/终止符）。
     *
     * @param excerpt 原文
     * @return 中和后文本
     */
    static String neutralize(String excerpt) {
        String[] lines = excerpt.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) {
                sb.append("\n");
            }
            boolean shaped = false;
            for (Pattern p : HARNESS_SHAPE_LINES) {
                if (p.matcher(line).find()) {
                    shaped = true;
                    break;
                }
            }
            sb.append(shaped ? "> " + line : line);
        }
        return sb.toString();
    }

    /**
     * 定位 search_result 数组的开中括号索引。
     *
     * @param json 响应文本
     * @return '[' 索引；找不到返回 -1
     */
    private static int locateResultArray(String json) {
        int ki = json.indexOf("\"search_result\"");
        if (ki < 0) {
            return -1;
        }
        int i = JsonStringScan.skipWs(json, ki + "\"search_result\"".length());
        if (i >= json.length() || json.charAt(i) != ':') {
            return -1;
        }
        i = JsonStringScan.skipWs(json, i + 1);
        return i < json.length() && json.charAt(i) == '[' ? i : -1;
    }

    /**
     * JSON 字符串字面量转义（含控制字符——RFC 8259）。
     *
     * @param s 原文
     * @return 转义后文本
     */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
