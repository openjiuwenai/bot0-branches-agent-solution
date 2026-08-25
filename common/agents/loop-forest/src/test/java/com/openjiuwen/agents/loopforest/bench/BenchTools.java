/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * v2 基准四工具——Python looprunner 工具面的忠实移植（B 臂复现实验的执行面）。
 *
 * <p>工具面钉死 {list_dir, read_file, grep, write_artifact}（锚 §1.3，无代码执行）。
 * 语义对照 Python：错误是数据不是异常（agent 可读 error 自纠）；read 上限取
 * Python 三档（32k/64k/128k）中值 64k；grep 行上限取中值 60——两常量 Python 侧
 * 是种子随机档，Java 固定中值（harness 差异记录在案）。
 *
 * @since 2026-08
 */
final class BenchTools {

    private static final int MAX_READ_CHARS = 65_536;
    private static final int MAX_GREP_LINES = 60;

    private final Path corpusDir;
    private final Path artifactDir;

    BenchTools(Path corpusDir, Path artifactDir) {
        this.corpusDir = corpusDir;
        this.artifactDir = artifactDir;
    }

    /**
     * 构造四工具实例（各自独立 ToolCard，注册走两步）。
     *
     * @return 工具列表
     */
    List<Tool> tools() {
        return List.of(new ListDirTool(), new ReadFileTool(), new GrepTool(),
                new WriteArtifactTool());
    }

    /** 路径安全解析——拒绝越出根目录（Python _safe_rel 等价）。 */
    private static Path safeRel(String rel, Path root) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root.normalize())) {
            throw new IllegalArgumentException("path escapes sandbox: " + rel);
        }
        return p;
    }

    /** 递归列文件——相对【corpus 根】的路径（子树也要全前缀：裸名会让模型拼错路径——
     * 4-lens 裁决实证子树裸名是工具失败死循环的供给端）。 */
    private List<String> walkSorted(Path base) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(base)) {
            s.filter(Files::isRegularFile).sorted().forEach(p ->
                    out.add(corpusDir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException e) {
            throw new IllegalStateException("walk failed: " + e.getMessage());
        }
        return out;
    }

    /** list_dir——递归列文件（相对 corpus 根，排序）。 */
    final class ListDirTool extends Tool {
        ListDirTool() {
            super(ToolCard.builder().id("list_dir").name("list_dir")
                    .description("List files under the corpus (recursive, sorted). "
                            + "Optional path narrows the subtree.")
                    .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of("path", Map.of("type", "string",
                                    "description", "Optional subdirectory path")),
                            "required", List.of()))
                    .build());
        }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            try {
                Path base = args.get("path") != null && !String.valueOf(args.get("path")).isBlank()
                        ? safeRel(String.valueOf(args.get("path")), corpusDir)
                        : corpusDir;
                return Map.of("entries", walkSorted(base));
            } catch (Exception e) {
                return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public java.util.Iterator<Object> stream(Map<String, Object> args,
                Map<String, Object> kwargs) {
            return List.of((Object) invoke(args, kwargs)).iterator();
        }
    }

    /** read_file——读文件（64k 字符上限 + truncated 标志）。 */
    final class ReadFileTool extends Tool {
        ReadFileTool() {
            super(ToolCard.builder().id("read_file").name("read_file")
                    .description("Read a file's text content (up to 64k chars).")
                    .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of("path", Map.of("type", "string",
                                    "description", "File path")),
                            "required", List.of("path")))
                    .build());
        }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            String rel = String.valueOf(args.getOrDefault("path", ""));
            try {
                Path p = safeRel(rel, corpusDir);
                String text = Files.readString(p, StandardCharsets.UTF_8);
                boolean truncated = text.length() >= MAX_READ_CHARS;
                if (truncated) {
                    text = text.substring(0, MAX_READ_CHARS);
                }
                return Map.of("path", rel, "text", text, "truncated", truncated);
            } catch (Exception e) {
                return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public java.util.Iterator<Object> stream(Map<String, Object> args,
                Map<String, Object> kwargs) {
            return List.of((Object) invoke(args, kwargs)).iterator();
        }
    }

    /** grep——正则扫文件（60 行上限，行文 200 字符）。 */
    final class GrepTool extends Tool {
        GrepTool() {
            super(ToolCard.builder().id("grep").name("grep")
                    .description("Regex search across corpus files. Returns matching lines "
                            + "with file and line number (capped at 60).")
                    .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "pattern", Map.of("type", "string",
                                            "description", "Regex pattern"),
                                    "path", Map.of("type", "string",
                                            "description", "Optional subtree path")),
                            "required", List.of("pattern")))
                    .build());
        }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            try {
                Pattern rx = Pattern.compile(String.valueOf(args.getOrDefault("pattern", "")));
                Path base = args.get("path") != null && !String.valueOf(args.get("path")).isBlank()
                        ? safeRel(String.valueOf(args.get("path")), corpusDir)
                        : corpusDir;
                List<Map<String, Object>> hits = new ArrayList<>();
                for (Path p : walkSorted(base).stream()
                        .map(rel -> corpusDir.resolve(rel))
                        .toList()) {
                    if (hits.size() >= MAX_GREP_LINES) {
                        break;
                    }
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size() && hits.size() < MAX_GREP_LINES; i++) {
                        if (rx.matcher(lines.get(i)).find()) {
                            String t = lines.get(i);
                            hits.add(Map.of(
                                    "file", corpusDir.relativize(p).toString().replace('\\', '/'),
                                    "line", i + 1,
                                    "text", t.length() > 200 ? t.substring(0, 200) : t));
                        }
                    }
                }
                return Map.of("matches", hits, "capped", hits.size() >= MAX_GREP_LINES);
            } catch (Exception e) {
                return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public java.util.Iterator<Object> stream(Map<String, Object> args,
                Map<String, Object> kwargs) {
            return List.of((Object) invoke(args, kwargs)).iterator();
        }
    }

    /** write_artifact——工件写盘（out/ 下；content 字符串或对象）。 */
    final class WriteArtifactTool extends Tool {
        WriteArtifactTool() {
            super(ToolCard.builder().id("write_artifact").name("write_artifact")
                    .description("Write an artifact file under out/. Call this EARLY "
                            + "with a partial draft, then call it again to revise. "
                            + "Content is the artifact fields as a JSON object.")
                    .inputParams(Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "path", Map.of("type", "string",
                                            "description", "Artifact path, e.g. out/v2a5.json"),
                                    "content", Map.of("type", "object",
                                            "description", "Artifact content as a JSON "
                                            + "object (fields written directly, not a "
                                            + "string)")),
                            "required", List.of("path", "content")))
                    .build());
        }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            String rel = String.valueOf(args.getOrDefault("path", ""));
            Object content = args.get("content");
            try {
                Path target = safeRel(rel, artifactDir);
                Files.createDirectories(target.getParent() != null
                        ? target.getParent() : artifactDir);
                String text;
                if (content == null) {
                    text = "{}";
                } else if (content instanceof Map || content instanceof List) {
                    text = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(content);
                } else {
                    text = String.valueOf(content);
                }
                Files.writeString(target, text, StandardCharsets.UTF_8);
                return Map.of("path", rel, "bytes", text.length());
            } catch (Exception e) {
                return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public java.util.Iterator<Object> stream(Map<String, Object> args,
                Map<String, Object> kwargs) {
            return List.of((Object) invoke(args, kwargs)).iterator();
        }
    }
}
