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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Sandbox-backed tooling rail for scenario 3 of {@code deep-research-sandbox-plan}:
 * multi-source data aggregated into a comparison table + chart via pandas/matplotlib
 * executed inside a jiuwenbox sandbox. Rendered PNGs are downloaded back into the
 * workspace {@code reports/} folder so the DeepAgent can reference them in the final
 * report.
 *
 * <p>Registers two harness tools on {@link DeepAgent}:
 * <ul>
 *   <li>{@code render_comparison_table(rows, title)} — DataFrame → Markdown table + PNG</li>
 *   <li>{@code render_chart(rows, chart_type, x_key, y_keys, title)} — matplotlib chart PNG</li>
 * </ul>
 *
 * <p>Library-tier only: depends solely on {@code agent-core-java} (via harness rail
 * extension classes) plus this package's own {@link SandboxOps} interface. The
 * sandbox facade type is intentionally library-owned so the runtime wrapper can
 * adapt any concrete sandbox client (jiuwenbox / e2b / etc.) into {@link SandboxOps}
 * without leaking runtime or core-java sandbox types into this tier.
 *
 * @since 2026-07-06
 */
public class SandboxRail extends DeepAgentRail {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PIP_INDEX_URL = firstNonBlank(
            System.getProperty("openjiuwen.sandbox.pip-index-url"),
            System.getenv("OPENJIUWEN_SANDBOX_PIP_INDEX_URL")).orElse(null);
    private static final String PIP_TRUSTED_HOST = firstNonBlank(
            System.getProperty("openjiuwen.sandbox.pip-trusted-host"),
            System.getenv("OPENJIUWEN_SANDBOX_PIP_TRUSTED_HOST")).orElse(null);
    private static final int EXEC_TIMEOUT_SECONDS = parseIntOrDefault(
            firstNonBlank(
                    System.getProperty("openjiuwen.sandbox.exec-timeout-seconds"),
                    System.getenv("OPENJIUWEN_SANDBOX_EXEC_TIMEOUT_SECONDS")).orElse(null),
            240);
    private static final String PNG_MARKER = "PNG_PATH:";
    private static final String TABLE_BEGIN = "MARKDOWN_TABLE_BEGIN";
    private static final String TABLE_END = "MARKDOWN_TABLE_END";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final String PY_PREAMBLE = ""
            + "import base64, json, os, sys, subprocess\n"
            + "PIP_EXTRA_ARGS = " + pipExtraArgsLiteral() + "\n"
            + "PIP_TARGET = '/tmp/pylibs'\n"
            + "if PIP_TARGET not in sys.path:\n"
            + "    sys.path.insert(0, PIP_TARGET)\n"
            + "os.environ.setdefault('MPLCONFIGDIR', '/tmp/mplconfig')\n"
            + "os.makedirs(os.environ['MPLCONFIGDIR'], exist_ok=True)\n"
            + "def _b64d(s):\n"
            + "    return base64.b64decode(s).decode('utf-8')\n"
            + "def _ensure(pkgs):\n"
            + "    os.makedirs(PIP_TARGET, exist_ok=True)\n"
            + "    _r = subprocess.run([sys.executable, '-m', 'pip', 'install',"
            + " '--no-cache-dir', '--target', PIP_TARGET, *PIP_EXTRA_ARGS, *pkgs],"
            + " capture_output=True, text=True)\n"
            + "    if _r.returncode != 0:\n"
            + "        print('PIP_INSTALL_STDOUT:', _r.stdout, file=sys.stderr)\n"
            + "        print('PIP_INSTALL_STDERR:', _r.stderr, file=sys.stderr)\n"
            + "        raise RuntimeError('pip install failed exit=' + str(_r.returncode)"
            + " + ' pkgs=' + str(pkgs))\n"
            + "    import importlib\n"
            + "    importlib.invalidate_caches()\n"
            + "    print('PIP_TARGET_INSTALLED:', sorted(os.listdir(PIP_TARGET))[:20],"
            + " file=sys.stderr)\n"
            + "try:\n"
            + "    import pandas as pd\n"
            + "    import matplotlib\n"
            + "    matplotlib.use('Agg')\n"
            + "    import matplotlib.pyplot as plt\n"
            + "    import tabulate  # noqa: F401 — required by DataFrame.to_markdown\n"
            + "    import mplfonts  # noqa: F401 — CJK-capable font provider for matplotlib\n"
            + "except ImportError:\n"
            + "    _ensure(['pandas', 'matplotlib', 'tabulate', 'mplfonts'])\n"
            + "    import pandas as pd\n"
            + "    import matplotlib\n"
            + "    matplotlib.use('Agg')\n"
            + "    import matplotlib.pyplot as plt\n"
            + "    import tabulate  # noqa: F401\n"
            + "    import mplfonts  # noqa: F401\n"
            + "import glob as _glob\n"
            + "from matplotlib import font_manager as _fm\n"
            + "try:\n"
            + "    import mplfonts as _mpf\n"
            + "    _pkg_dir = os.path.dirname(_mpf.__file__)\n"
            + "    _font_files = []\n"
            + "    for _ext in ('*.otf', '*.ttf', '*.ttc'):\n"
            + "        _font_files.extend(_glob.glob(os.path.join(_pkg_dir, '**', _ext),"
            + " recursive=True))\n"
            + "    print('MPLFONTS_BUNDLED:', _font_files, file=sys.stderr)\n"
            + "    for _f in _font_files:\n"
            + "        try:\n"
            + "            _fm.fontManager.addfont(_f)\n"
            + "        except Exception as _e:\n"
            + "            print('ADDFONT_FAILED:', _f, _e, file=sys.stderr)\n"
            + "except ImportError as _e:\n"
            + "    print('MPLFONTS_IMPORT_FAILED:', _e, file=sys.stderr)\n"
            + "_CANDIDATES = ('Noto Sans CJK SC', 'Noto Sans Mono CJK SC', 'Noto Sans SC',"
            + " 'Source Han Sans SC', 'Source Han Sans CN', 'WenQuanYi Zen Hei',"
            + " 'WenQuanYi Micro Hei', 'SimHei', 'Microsoft YaHei', 'PingFang SC',"
            + " 'Arial Unicode MS')\n"
            + "_available_names = {f.name for f in _fm.fontManager.ttflist}\n"
            + "_picked = None\n"
            + "for _name in _CANDIDATES:\n"
            + "    if _name in _available_names:\n"
            + "        _picked = _name\n"
            + "        break\n"
            + "if _picked is not None:\n"
            + "    matplotlib.rcParams['font.sans-serif'] = [_picked] + ["
            + "n for n in _CANDIDATES if n != _picked and n in _available_names"
            + "] + ['DejaVu Sans']\n"
            + "    print('CJK_FONT_SET:', _picked, file=sys.stderr)\n"
            + "else:\n"
            + "    matplotlib.rcParams['font.sans-serif'] = ['DejaVu Sans']\n"
            + "    print('CJK_FONT_NONE_AVAILABLE. sample_registered:',"
            + " sorted(_available_names)[:40], file=sys.stderr)\n"
            + "matplotlib.rcParams['axes.unicode_minus'] = False\n";

    private static final String PY_TABLE_BODY = ""
            + "rows = json.loads(ROWS_JSON)\n"
            + "df = pd.DataFrame(rows)\n"
            + "md = df.to_markdown(index=False)\n"
            + "print('MARKDOWN_TABLE_BEGIN')\n"
            + "print(md)\n"
            + "print('MARKDOWN_TABLE_END')\n"
            + "fig, ax = plt.subplots(figsize=(min(2 + 1.4*len(df.columns), 16),"
            + " min(1.2 + 0.4*len(df), 12)))\n"
            + "ax.axis('off')\n"
            + "ax.set_title(TITLE, fontsize=13, pad=12)\n"
            + "cells = df.astype(str).values.tolist()\n"
            + "tbl = ax.table(cellText=cells, colLabels=list(df.columns),"
            + " loc='center', cellLoc='center')\n"
            + "tbl.auto_set_font_size(False)\n"
            + "tbl.set_fontsize(10)\n"
            + "tbl.scale(1.0, 1.4)\n"
            + "fig.tight_layout()\n"
            + "fig.savefig(PNG_PATH, dpi=150, bbox_inches='tight')\n"
            + "plt.close(fig)\n"
            + "print('ROW_COUNT:' + str(len(df)))\n"
            + "print('COL_COUNT:' + str(len(df.columns)))\n"
            + "print('PNG_PATH:' + PNG_PATH)\n";

    private static final String PY_CHART_BODY = ""
            + "rows = json.loads(ROWS_JSON)\n"
            + "df = pd.DataFrame(rows)\n"
            + "if X_KEY not in df.columns:\n"
            + "    raise SystemExit('x_key ' + X_KEY + ' not in columns: ' + str(list(df.columns)))\n"
            + "missing = [k for k in Y_KEYS if k not in df.columns]\n"
            + "if missing:\n"
            + "    raise SystemExit('y_keys missing from rows: ' + str(missing))\n"
            + "for k in Y_KEYS:\n"
            + "    df[k] = pd.to_numeric(df[k], errors='coerce')\n"
            + "fig, ax = plt.subplots(figsize=(max(6, 1.2*len(df)), 5))\n"
            + "x_vals = df[X_KEY].astype(str).tolist()\n"
            + "if CHART_TYPE == 'bar':\n"
            + "    width = 0.8 / max(1, len(Y_KEYS))\n"
            + "    import numpy as np\n"
            + "    xs = np.arange(len(x_vals))\n"
            + "    for i, k in enumerate(Y_KEYS):\n"
            + "        ax.bar(xs + i*width, df[k].tolist(), width=width, label=k)\n"
            + "    ax.set_xticks(xs + width*(len(Y_KEYS)-1)/2)\n"
            + "    ax.set_xticklabels(x_vals, rotation=20, ha='right')\n"
            + "elif CHART_TYPE == 'line':\n"
            + "    for k in Y_KEYS:\n"
            + "        ax.plot(x_vals, df[k].tolist(), marker='o', label=k)\n"
            + "    ax.tick_params(axis='x', rotation=20)\n"
            + "else:\n"
            + "    for k in Y_KEYS:\n"
            + "        ax.scatter(x_vals, df[k].tolist(), label=k)\n"
            + "    ax.tick_params(axis='x', rotation=20)\n"
            + "ax.set_title(TITLE)\n"
            + "ax.set_xlabel(X_KEY)\n"
            + "ax.legend()\n"
            + "ax.grid(True, alpha=0.3)\n"
            + "fig.tight_layout()\n"
            + "fig.savefig(PNG_PATH, dpi=150, bbox_inches='tight')\n"
            + "plt.close(fig)\n"
            + "print('PNG_PATH:' + PNG_PATH)\n";

    private final Supplier<SandboxOps> opsSupplier;
    private final String workspacePath;
    private final List<Tool> ownedTools = new ArrayList<>();
    private final AtomicInteger callSeq = new AtomicInteger();

    /**
     * Create a sandbox rail.
     *
     * @param opsSupplier supplier of the sandbox facade; called on each tool invocation
     *     so the rail picks up runtime-provisioned or dynamically re-created backends
     * @param workspacePath absolute or relative workspace root; PNG downloads land under
     *     {@code <workspacePath>/reports/}
     */
    public SandboxRail(Supplier<SandboxOps> opsSupplier, String workspacePath) {
        if (opsSupplier == null) {
            throw new IllegalArgumentException("opsSupplier is required");
        }
        this.opsSupplier = opsSupplier;
        this.workspacePath = workspacePath == null || workspacePath.isBlank()
                ? "target/deep-research-workspace"
                : workspacePath;
    }

    @Override
    public int priority() {
        return 60;
    }

    @Override
    public void init(Object agent) {
        if (!(agent instanceof DeepAgent deepAgent)) {
            return;
        }
        LocalFunction table = new LocalFunction(buildTableCard(), this::renderComparisonTable);
        LocalFunction chart = new LocalFunction(buildChartCard(), this::renderChart);
        deepAgent.registerHarnessTool(table);
        deepAgent.registerHarnessTool(chart);
        ownedTools.add(table);
        ownedTools.add(chart);
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

    private ToolCard buildTableCard() {
        Map<String, Object> rowsProp = new LinkedHashMap<>();
        rowsProp.put("type", "array");
        rowsProp.put("description",
                "Array of objects; each object is one row. All rows should share the same keys.");
        rowsProp.put("items", Map.of("type", "object"));

        Map<String, Object> titleProp = new LinkedHashMap<>();
        titleProp.put("type", "string");
        titleProp.put("description", "Title shown above the table (also used in the PNG).");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rows", rowsProp);
        properties.put("title", titleProp);

        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("type", "object");
        inputParams.put("properties", properties);
        inputParams.put("required", List.of("rows", "title"));

        return ToolCard.builder()
                .id("sandbox_render_comparison_table")
                .name("render_comparison_table")
                .description("Render a comparison table from multi-source rows via pandas inside "
                        + "the sandbox. Returns a Markdown table plus a PNG path in the workspace. "
                        + "Call this once vendor × dimension cells are collected; use the Markdown "
                        + "in the final report and cite the PNG for reviewers.")
                .inputParams(inputParams)
                .build();
    }

    private ToolCard buildChartCard() {
        Map<String, Object> rowsProp = new LinkedHashMap<>();
        rowsProp.put("type", "array");
        rowsProp.put("description", "Array of objects; each object is one row.");
        rowsProp.put("items", Map.of("type", "object"));

        Map<String, Object> chartTypeProp = new LinkedHashMap<>();
        chartTypeProp.put("type", "string");
        chartTypeProp.put("enum", List.of("bar", "line", "scatter"));
        chartTypeProp.put("description", "Chart type.");

        Map<String, Object> xKeyProp = new LinkedHashMap<>();
        xKeyProp.put("type", "string");
        xKeyProp.put("description", "Row key to use for the x-axis (typically vendor).");

        Map<String, Object> yKeysProp = new LinkedHashMap<>();
        yKeysProp.put("type", "array");
        yKeysProp.put("items", Map.of("type", "string"));
        yKeysProp.put("description", "One or more row keys to plot on the y-axis "
                + "(numeric fields only).");

        Map<String, Object> titleProp = new LinkedHashMap<>();
        titleProp.put("type", "string");
        titleProp.put("description", "Chart title.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("rows", rowsProp);
        properties.put("chart_type", chartTypeProp);
        properties.put("x_key", xKeyProp);
        properties.put("y_keys", yKeysProp);
        properties.put("title", titleProp);

        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("type", "object");
        inputParams.put("properties", properties);
        inputParams.put("required", List.of("rows", "chart_type", "x_key", "y_keys", "title"));

        return ToolCard.builder()
                .id("sandbox_render_chart")
                .name("render_chart")
                .description("Render a bar/line/scatter chart from multi-source rows via matplotlib "
                        + "inside the sandbox. Returns a PNG path in the workspace. Use this to "
                        + "visualise numeric comparisons (e.g. input token price by vendor).")
                .inputParams(inputParams)
                .build();
    }

    private Object renderComparisonTable(Map<String, Object> inputs) {
        String title = stringOrDefault(inputs.get("title"), "Comparison");
        RowParseResult rows = serializeRows(inputs.get("rows"));
        if (rows.error() != null) {
            return errorResult(rows.error());
        }
        int seq = callSeq.incrementAndGet();
        String ts = timestamp();
        String filename = "render_table_" + ts + "_" + seq + ".png";
        String sandboxPng = "/tmp/deepresearch_table_" + ts + "_" + seq + ".png";
        String localPng = localReportPath(filename);
        String relPath = "reports/" + filename;

        String code = buildTablePython(rows.json(), title, sandboxPng);
        return runSandbox(code, localPng, relPath, RenderKind.TABLE);
    }

    private Object renderChart(Map<String, Object> inputs) {
        String chartType = stringOrDefault(inputs.get("chart_type"), "bar")
                .toLowerCase(Locale.ROOT);
        if (!List.of("bar", "line", "scatter").contains(chartType)) {
            return errorResult("chart_type must be bar|line|scatter (got '" + chartType + "')");
        }
        String xKey = stringOrDefault(inputs.get("x_key"), "");
        if (xKey.isBlank()) {
            return errorResult("x_key is required");
        }
        List<String> yKeys = coerceStringList(inputs.get("y_keys"));
        if (yKeys.isEmpty()) {
            return errorResult("y_keys must be a non-empty array of strings");
        }
        RowParseResult rows = serializeRows(inputs.get("rows"));
        if (rows.error() != null) {
            return errorResult(rows.error());
        }
        int seq = callSeq.incrementAndGet();
        String ts = timestamp();
        String filename = "render_chart_" + ts + "_" + seq + ".png";
        String sandboxPng = "/tmp/deepresearch_chart_" + ts + "_" + seq + ".png";
        String localPng = localReportPath(filename);
        String relPath = "reports/" + filename;

        String ykJson;
        try {
            ykJson = MAPPER.writeValueAsString(yKeys);
        } catch (JsonProcessingException ex) {
            return errorResult("failed to serialise y_keys: " + ex.getMessage());
        }
        String title = stringOrDefault(inputs.get("title"), "Chart");
        String code = buildChartPython(new ChartRequest(rows.json(), title, chartType, xKey, ykJson,
                sandboxPng));
        return runSandbox(code, localPng, relPath, RenderKind.CHART);
    }

    private Map<String, Object> runSandbox(String code, String localPng,
                                           String relPngPath, RenderKind kind) {
        SandboxOps ops = opsSupplier.get();
        if (ops == null) {
            return errorResult("sandbox ops supplier returned null");
        }
        ExecResult result = ops.executeCode(code, EXEC_TIMEOUT_SECONDS);
        if (result == null) {
            return errorResult("sandbox ops returned null result");
        }
        if (!result.isOk()) {
            Map<String, Object> err = errorResult("sandbox python exit=" + result.exitCode());
            err.put("message", result.message());
            err.put("stderr_tail", tail(result.stderr(), 2000));
            return err;
        }
        String stdout = result.stdout();
        Optional<String> reportedPng = extractMarker(stdout, PNG_MARKER);
        if (reportedPng.isEmpty()) {
            Map<String, Object> err = errorResult("sandbox did not emit " + PNG_MARKER + " marker");
            err.put("stdout_tail", tail(stdout, 2000));
            err.put("stderr_tail", tail(result.stderr(), 2000));
            return err;
        }
        return buildSuccessResult(new SuccessContext(ops, kind, stdout,
                new RenderPaths(reportedPng.get(), localPng, relPngPath)));
    }

    private Map<String, Object> buildSuccessResult(SuccessContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        String stdout = ctx.stdout();
        if (ctx.kind() == RenderKind.TABLE) {
            extractBlock(stdout, TABLE_BEGIN, TABLE_END).ifPresent(md -> out.put("markdown_table", md));
            extractMarker(stdout, "ROW_COUNT:").ifPresent(raw -> {
                OptionalInt parsed = parseIntOptional(raw);
                if (parsed.isPresent()) {
                    out.put("row_count", parsed.getAsInt());
                }
            });
            extractMarker(stdout, "COL_COUNT:").ifPresent(raw -> {
                OptionalInt parsed = parseIntOptional(raw);
                if (parsed.isPresent()) {
                    out.put("column_count", parsed.getAsInt());
                }
            });
        }
        RenderPaths paths = ctx.paths();
        Optional<String> downloaded = downloadPng(ctx.ops(), paths.sandboxPng(), paths.localPng());
        out.put("sandbox_png_path", paths.sandboxPng());
        if (downloaded.isPresent()) {
            out.put("png_path", paths.relPngPath());
            out.put("png_absolute_path", downloaded.get());
        } else {
            out.put("png_path", null);
            out.put("download_warning",
                    "sandbox rendered PNG but download to workspace failed; check sandbox_png_path");
        }
        return out;
    }

    private Optional<String> downloadPng(SandboxOps ops, String sandboxPng, String localPng) {
        ensureParentDir(localPng);
        return ops.downloadFile(sandboxPng, localPng);
    }

    private String localReportPath(String filename) {
        Path base = Paths.get(workspacePath, "reports");
        return base.resolve(filename).toString();
    }

    private void ensureParentDir(String pathStr) {
        try {
            Path parent = Paths.get(pathStr).getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException | InvalidPathException ignored) {
            // Best-effort: downloadFile will try to create parent dirs too (isCreateParentDirs=true).
        }
    }

    private RowParseResult serializeRows(Object rowsRaw) {
        if (rowsRaw == null) {
            return RowParseResult.error("rows is required (got null)");
        }
        try {
            List<Map<String, Object>> rows;
            if (rowsRaw instanceof List<?> list) {
                rows = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (!(item instanceof Map<?, ?> map)) {
                        return RowParseResult.error(
                                "rows[" + i + "] is not an object: "
                                        + (item == null ? "null" : item.getClass().getSimpleName()));
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) map;
                    rows.add(row);
                }
            } else if (rowsRaw instanceof String jsonStr) {
                rows = MAPPER.readValue(jsonStr, new TypeReference<>() {});
            } else {
                return RowParseResult.error(
                        "rows must be array of objects or JSON string, got "
                                + rowsRaw.getClass().getSimpleName());
            }
            if (rows.isEmpty()) {
                return RowParseResult.error("rows must be non-empty");
            }
            return RowParseResult.ok(MAPPER.writeValueAsString(rows));
        } catch (IOException | ClassCastException ex) {
            return RowParseResult.error("failed to parse rows: " + ex.getMessage());
        }
    }

    private String buildTablePython(String rowsJson, String title, String pngPath) {
        String rowsB64 = Base64.getEncoder().encodeToString(rowsJson.getBytes(StandardCharsets.UTF_8));
        String titleB64 = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));
        return PY_PREAMBLE
                + "ROWS_JSON = _b64d('" + rowsB64 + "')\n"
                + "TITLE     = _b64d('" + titleB64 + "')\n"
                + "PNG_PATH  = " + pyStr(pngPath) + "\n"
                + PY_TABLE_BODY;
    }

    private String buildChartPython(ChartRequest req) {
        String rowsB64 = Base64.getEncoder().encodeToString(req.rowsJson().getBytes(StandardCharsets.UTF_8));
        String titleB64 = Base64.getEncoder().encodeToString(req.title().getBytes(StandardCharsets.UTF_8));
        String xB64 = Base64.getEncoder().encodeToString(req.xKey().getBytes(StandardCharsets.UTF_8));
        String yB64 = Base64.getEncoder().encodeToString(req.yKeysJson().getBytes(StandardCharsets.UTF_8));
        return PY_PREAMBLE
                + "ROWS_JSON  = _b64d('" + rowsB64 + "')\n"
                + "TITLE      = _b64d('" + titleB64 + "')\n"
                + "X_KEY      = _b64d('" + xB64 + "')\n"
                + "Y_KEYS     = json.loads(_b64d('" + yB64 + "'))\n"
                + "CHART_TYPE = " + pyStr(req.chartType()) + "\n"
                + "PNG_PATH   = " + pyStr(req.pngPath()) + "\n"
                + PY_CHART_BODY;
    }

    private static String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static List<String> coerceStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (value instanceof String csv && !csv.isBlank()) {
            for (String piece : csv.split(",")) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        } else {
            return out;
        }
        return out;
    }

    private static Optional<String> extractMarker(String stdout, String marker) {
        int idx = stdout.indexOf(marker);
        if (idx < 0) {
            return Optional.empty();
        }
        int start = idx + marker.length();
        int end = stdout.indexOf('\n', start);
        String value = end < 0 ? stdout.substring(start) : stdout.substring(start, end);
        return Optional.of(value.trim());
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

    private static OptionalInt parseIntOptional(String value) {
        try {
            return OptionalInt.of(Integer.parseInt(value.trim()));
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }

    private static int parseIntOrDefault(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String timestamp() {
        return LocalDateTime.now().format(TS_FMT);
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message);
        return map;
    }

    private static String tail(String source, int max) {
        if (source == null) {
            return "";
        }
        return source.length() <= max ? source : "..." + source.substring(source.length() - max);
    }

    private static Optional<String> firstNonBlank(String... vals) {
        for (String candidate : vals) {
            if (candidate != null && !candidate.isBlank()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String pyStr(String source) {
        return "'" + source.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String pipExtraArgsLiteral() {
        List<String> args = new ArrayList<>();
        if (PIP_INDEX_URL != null) {
            args.add("-i");
            args.add(PIP_INDEX_URL);
        }
        if (PIP_TRUSTED_HOST != null) {
            args.add("--trusted-host");
            args.add(PIP_TRUSTED_HOST);
        }
        try {
            return MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private enum RenderKind {
        TABLE, CHART
    }

    private record ChartRequest(String rowsJson, String title, String chartType,
                                String xKey, String yKeysJson, String pngPath) {
    }

    private record RowParseResult(String json, String error) {
        static RowParseResult ok(String json) {
            return new RowParseResult(json, null);
        }

        static RowParseResult error(String message) {
            return new RowParseResult(null, message);
        }
    }

    private record RenderPaths(String sandboxPng, String localPng, String relPngPath) {
    }

    private record SuccessContext(SandboxOps ops, RenderKind kind, String stdout, RenderPaths paths) {
    }
}
