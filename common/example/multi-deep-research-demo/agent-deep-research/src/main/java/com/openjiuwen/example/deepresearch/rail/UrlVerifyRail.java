/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.rail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sandbox-backed URL verification rail (scenario 1 of {@code deep-research-sandbox-plan}).
 *
 * <p>Registers a single harness tool {@code verify_urls(urls, [timeout_seconds])} on
 * {@link DeepAgent}. The tool issues concurrent HTTP GET requests from inside the sandbox
 * (via Python stdlib {@code urllib}, no extra pip installs) and returns, for each URL,
 * HTTP status + final URL after redirects + content-type + a short body snippet + elapsed ms.
 *
 * <p>Intended use: after the search agent produces citations, the DeepAgent can call this
 * tool to check that (a) URLs are reachable and (b) content actually mentions what the
 * report claims — cheap defence against LLM hallucinated URLs / stale sources.
 *
 * <p>Library-tier only — depends on {@code agent-core-java} + {@link SandboxOps}.
 *
 * @since 2026-07-06
 */
public class UrlVerifyRail extends DeepAgentRail {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int EXEC_TIMEOUT_SECONDS = 90;
    private static final int MAX_URLS_PER_CALL = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final int SNIPPET_TAIL_MAX = 2000;
    private static final String RESULT_BEGIN = "VERIFY_URLS_BEGIN";
    private static final String RESULT_END = "VERIFY_URLS_END";

    private static final String PY_VERIFY_BODY = ""
            + "MAX_BODY = 4096\n"
            + "SNIPPET_CHARS = 800\n"
            + "UA = 'openjiuwen-deep-research/0.1'\n"
            + "def _verify(url):\n"
            + "    start = time.time()\n"
            + "    out = {'url': url, 'ok': False}\n"
            + "    try:\n"
            + "        ctx = ssl.create_default_context()\n"
            + "        chain = []\n"
            + "        class _R(urllib.request.HTTPRedirectHandler):\n"
            + "            def redirect_request(self, req, fp, code, msg, headers, newurl):\n"
            + "                chain.append({'from': req.full_url, 'to': newurl, 'code': code})\n"
            + "                return super().redirect_request(req, fp, code, msg, headers, newurl)\n"
            + "        opener = urllib.request.build_opener(_R(),"
            + " urllib.request.HTTPSHandler(context=ctx))\n"
            + "        req = urllib.request.Request(url, method='GET', headers={'User-Agent': UA})\n"
            + "        resp = opener.open(req, timeout=TIMEOUT)\n"
            + "        raw = resp.read(MAX_BODY)\n"
            + "        try:\n"
            + "            body = raw.decode('utf-8', errors='replace')\n"
            + "        except Exception:\n"
            + "            body = ''\n"
            + "        out.update({\n"
            + "            'ok': True,\n"
            + "            'status': resp.status,\n"
            + "            'final_url': resp.geturl(),\n"
            + "            'redirect_chain': chain,\n"
            + "            'content_type': resp.headers.get('Content-Type', ''),\n"
            + "            'content_length': resp.headers.get('Content-Length'),\n"
            + "            'body_snippet': body[:SNIPPET_CHARS],\n"
            + "        })\n"
            + "    except urllib.error.HTTPError as e:\n"
            + "        try:\n"
            + "            raw = e.read(MAX_BODY) if hasattr(e, 'read') else b''\n"
            + "            body = raw.decode('utf-8', errors='replace')\n"
            + "        except Exception:\n"
            + "            body = ''\n"
            + "        out.update({\n"
            + "            'ok': False,\n"
            + "            'status': e.code,\n"
            + "            'final_url': getattr(e, 'url', url),\n"
            + "            'error': 'HTTP ' + str(e.code) + ' ' + str(e.reason),\n"
            + "            'body_snippet': body[:SNIPPET_CHARS],\n"
            + "        })\n"
            + "    except urllib.error.URLError as e:\n"
            + "        out['error'] = 'URLError: ' + str(e.reason)\n"
            + "    except Exception as e:\n"
            + "        out['error'] = type(e).__name__ + ': ' + str(e)\n"
            + "    out['elapsed_ms'] = int((time.time() - start) * 1000)\n"
            + "    return out\n"
            + "results = []\n"
            + "with concurrent.futures.ThreadPoolExecutor(max_workers=min(5, len(URLS))) as ex:\n"
            + "    for r in ex.map(_verify, URLS):\n"
            + "        results.append(r)\n";

    private final Supplier<SandboxOps> opsSupplier;
    private final List<Tool> ownedTools = new ArrayList<>();

    /**
     * Creates a new URL-verify rail.
     *
     * @param opsSupplier supplier of {@link SandboxOps} (evaluated per invocation)
     * @throws IllegalArgumentException if {@code opsSupplier} is {@code null}
     */
    public UrlVerifyRail(Supplier<SandboxOps> opsSupplier) {
        if (opsSupplier == null) {
            throw new IllegalArgumentException("opsSupplier is required");
        }
        this.opsSupplier = opsSupplier;
    }

    @Override
    public int priority() {
        return 65;
    }

    @Override
    public void init(Object agent) {
        if (!(agent instanceof DeepAgent deepAgent)) {
            return;
        }
        LocalFunction verify = new LocalFunction(buildVerifyCard(), this::verifyUrls);
        deepAgent.registerHarnessTool(verify);
        ownedTools.add(verify);
    }

    @Override
    public void uninit(Object agent) {
        if (agent instanceof DeepAgent deepAgent) {
            for (Tool tool : List.copyOf(ownedTools)) {
                deepAgent.unregisterHarnessTool(tool);
            }
        }
        ownedTools.clear();
    }

    private ToolCard buildVerifyCard() {
        Map<String, Object> urlsProp = new LinkedHashMap<>();
        urlsProp.put("type", "array");
        urlsProp.put("items", Map.of("type", "string"));
        urlsProp.put("description",
                "URLs to verify (up to " + MAX_URLS_PER_CALL + " per call).");

        Map<String, Object> timeoutProp = new LinkedHashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Per-URL timeout in seconds (default "
                + DEFAULT_TIMEOUT_SECONDS + ", capped at " + MAX_TIMEOUT_SECONDS + ").");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("urls", urlsProp);
        properties.put("timeout_seconds", timeoutProp);

        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("type", "object");
        inputParams.put("properties", properties);
        inputParams.put("required", List.of("urls"));

        return ToolCard.builder()
                .id("sandbox_verify_urls")
                .name("verify_urls")
                .description("Verify a batch of URLs from inside the sandbox: issues concurrent "
                        + "HTTP GET requests and returns HTTP status, final URL after redirects, "
                        + "content-type, a short body snippet, and elapsed ms for each URL. Use "
                        + "this to (a) confirm citations in the report are reachable and (b) spot "
                        + "check that the fetched content mentions what the report claims.")
                .inputParams(inputParams)
                .build();
    }

    private Object verifyUrls(Map<String, Object> inputs) {
        List<String> urls = coerceUrlList(inputs.get("urls"));
        if (urls.isEmpty()) {
            return errorResult("urls must be a non-empty array of strings");
        }
        if (urls.size() > MAX_URLS_PER_CALL) {
            return errorResult("urls limited to " + MAX_URLS_PER_CALL + " per call (got "
                    + urls.size() + ")");
        }

        String urlsJson;
        try {
            urlsJson = MAPPER.writeValueAsString(urls);
        } catch (JsonProcessingException ex) {
            return errorResult("failed to serialise urls: " + ex.getMessage());
        }

        int timeout = clampTimeout(inputs.get("timeout_seconds"));
        String code = buildVerifyPython(urlsJson, timeout);
        SandboxOps ops = opsSupplier.get();
        ExecResult result = ops.executeCode(code, EXEC_TIMEOUT_SECONDS);
        return buildResult(result);
    }

    private Object buildResult(ExecResult result) {
        if (!result.isOk()) {
            Map<String, Object> err = errorResult("sandbox python exit=" + result.exitCode());
            err.put("message", result.message());
            err.put("stderr_tail", tail(result.stderr(), SNIPPET_TAIL_MAX));
            return err;
        }

        String stdout = result.stdout();
        Optional<String> jsonBlock = extractBlock(stdout, RESULT_BEGIN, RESULT_END);
        if (jsonBlock.isEmpty()) {
            Map<String, Object> err = errorResult("sandbox did not emit "
                    + RESULT_BEGIN + " block");
            err.put("stdout_tail", tail(stdout, SNIPPET_TAIL_MAX));
            err.put("stderr_tail", tail(result.stderr(), SNIPPET_TAIL_MAX));
            return err;
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(jsonBlock.get(), new TypeReference<>() {});
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("results", parsed.get("urls"));
            return out;
        } catch (IOException ex) {
            Map<String, Object> err = errorResult("failed to parse verify_urls output: "
                    + ex.getMessage());
            err.put("stdout_tail", tail(stdout, SNIPPET_TAIL_MAX));
            return err;
        }
    }

    private static int clampTimeout(Object raw) {
        int seconds;
        if (raw instanceof Number num) {
            seconds = num.intValue();
        } else if (raw instanceof String str && !str.isBlank()) {
            seconds = parseTimeoutString(str.trim());
        } else {
            seconds = DEFAULT_TIMEOUT_SECONDS;
        }
        if (seconds < 1) {
            return 1;
        }
        return Math.min(seconds, MAX_TIMEOUT_SECONDS);
    }

    private static int parseTimeoutString(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    private static List<String> coerceUrlList(Object raw) {
        if (raw instanceof List<?> list) {
            return coerceListToUrls(list);
        } else if (raw instanceof String str && !str.isBlank()) {
            return coerceStringToUrls(str);
        } else {
            return new ArrayList<>();
        }
    }

    private static List<String> coerceListToUrls(List<?> list) {
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String trimmed = String.valueOf(item).trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> coerceStringToUrls(String src) {
        List<String> out = new ArrayList<>();
        for (String piece : src.split("[,\\s]+")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message);
        return map;
    }

    private static Optional<String> extractBlock(String stdout, String beginMarker, String endMarker) {
        int begin = stdout.indexOf(beginMarker);
        int end = stdout.indexOf(endMarker);
        if (begin < 0 || end < 0 || end <= begin) {
            return Optional.empty();
        }
        int contentStart = stdout.indexOf('\n', begin);
        if (contentStart < 0 || contentStart >= end) {
            return Optional.empty();
        }
        return Optional.of(stdout.substring(contentStart + 1, end).trim());
    }

    private static String tail(String src, int max) {
        if (src == null) {
            return "";
        }
        return src.length() <= max ? src : "..." + src.substring(src.length() - max);
    }

    private static String buildVerifyPython(String urlsJson, int timeoutSeconds) {
        String urlsB64 = Base64.getEncoder().encodeToString(urlsJson.getBytes(StandardCharsets.UTF_8));
        return ""
                + "import base64, json, sys, time, ssl\n"
                + "import urllib.request, urllib.error\n"
                + "import concurrent.futures\n"
                + "URLS = json.loads(base64.b64decode('" + urlsB64 + "').decode('utf-8'))\n"
                + "TIMEOUT = " + timeoutSeconds + "\n"
                + PY_VERIFY_BODY
                + "print('" + RESULT_BEGIN + "')\n"
                + "print(json.dumps({'urls': results}, ensure_ascii=False))\n"
                + "print('" + RESULT_END + "')\n";
    }
}
