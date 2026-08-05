/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.agent.rail;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded {@code readFile} rail for the deep-research demo.
 *
 * <p>Motivation (FEAT-005 layer-3 closure): SkillHub delivers SKILL.md files onto the
 * agent's SkillManager, and {@code ReActAgent.updateSkillPromptBuilderSection}
 * auto-injects a system-prompt section that <em>hardcodes</em> the instruction
 * "use the readFile tool to read the corresponding SKILL.md file" (see
 * {@code SkillUtil.getSkillPrompt} + {@code ReActAgent.warnMissingSkillReadFileTool},
 * both of which look up the tool by the exact camelCase name {@code readFile}).
 * Without a tool at that name, {@link SkillObservationRail} confirms the LLM sees
 * the skill names but has no way to read the body — the loop terminates without
 * skill consumption.
 *
 * <p>Registers one harness tool on {@link DeepAgent}:
 * <ul>
 *   <li>{@code readFile(file_path)} — read a UTF-8 text file that lives under one
 *       of the configured allowed roots. Returns {@code {ok, path, bytes, content}}
 *       on success; {@code {ok=false, path, error}} on any validation failure.
 *       The name is {@code readFile} (camelCase, not {@code read_file}) because
 *       that is the exact string the core-java skill prompt tells the LLM to use.</li>
 * </ul>
 *
 * <p>Safety envelope (defence-in-depth for a demo tool that reads local disk):
 * <ul>
 *   <li>Absolute-path canonicalisation via {@link Path#toAbsolutePath()} +
 *       {@link Path#normalize()} strips {@code ..} segments and yields a stable form
 *       for ancestor comparison.</li>
 *   <li>Every read must resolve to a descendant of at least one canonical allowed
 *       root; anything else is rejected before the file is opened.</li>
 *   <li>Hard {@value #MAX_BYTES}-byte cap on file size — refuse to load anything
 *       larger, so a stray reference to a big binary can't blow context.</li>
 *   <li>UTF-8 decoding only. Files that fail to decode surface as an explicit
 *       error, not as garbled content.</li>
 *   <li>The success log line records only path basename + byte count, never the
 *       body — keeps the rail compatible with DA-12's response redaction policy.</li>
 * </ul>
 *
 * <p>Allowed-root policy: pass every directory the LLM should be able to read from
 * (typically the DeepAgent workspace root plus SkillHub's {@code localDir}). Blank
 * or missing entries are dropped silently at construction time; if no roots survive
 * the rail refuses all reads (fail-closed, not fail-open).
 *
 * @since 2026-07-26
 */
public class SkillReadFileRail extends DeepAgentRail {
    private static final Logger LOG = LoggerFactory.getLogger(SkillReadFileRail.class);

    /** Hard cap on file size we're willing to serve to the LLM (64 KB). */
    private static final long MAX_BYTES = 64L * 1024L;

    private static final int DEFAULT_PRIORITY = 70;

    private final List<Path> allowedRoots;
    private final List<Tool> ownedTools = new ArrayList<>();

    /**
     * Create a read-file rail bound to the given allowed roots.
     *
     * @param allowedRoots directories under which the {@code readFile} tool may
     *     serve files; each entry is canonicalised at construction time and
     *     blank/invalid entries are dropped. A {@code null} or empty list yields
     *     a fail-closed rail (every read attempt is rejected).
     */
    public SkillReadFileRail(List<String> allowedRoots) {
        this.allowedRoots = canonicaliseRoots(allowedRoots);
        LOG.info("skill_readFile_rail init allowed_roots={}", this.allowedRoots);
    }

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public void init(Object agent) {
        if (!(agent instanceof DeepAgent deepAgent)) {
            return;
        }
        LocalFunction fn = new LocalFunction(buildCard(), this::readFile);
        deepAgent.registerHarnessTool(fn);
        ownedTools.add(fn);
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

    private ToolCard buildCard() {
        Map<String, Object> filePathProp = new LinkedHashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description",
                "Absolute path to the file to read. Must be under one of the "
                        + "rail's configured allowed roots (workspace root plus any "
                        + "extra readable roots such as the SkillHub local dir). "
                        + "Use paths returned by tools like list_skill or paths "
                        + "printed in earlier tool results; do not invent them.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", filePathProp);

        Map<String, Object> inputParams = new LinkedHashMap<>();
        inputParams.put("type", "object");
        inputParams.put("properties", properties);
        inputParams.put("required", List.of("file_path"));

        return ToolCard.builder()
                .id("deep_research_read_file")
                .name("readFile")
                .description("Read a small UTF-8 text file (<= 64 KB) that lives under "
                        + "one of the demo's allow-listed roots. This is the tool the "
                        + "core-java skill prompt tells you to call when it lists "
                        + "\"Skill directory file path: <dir>\" — append \"/SKILL.md\" "
                        + "to that directory and pass the full path as file_path. Also "
                        + "usable for other artefacts written into the DeepAgent "
                        + "workspace. Returns {ok, path, bytes, content} on success; "
                        + "{ok=false, path, error} on any validation failure. Never "
                        + "use it to read arbitrary system paths — the rail rejects "
                        + "anything outside the allowed roots.")
                .inputParams(inputParams)
                .build();
    }

    private Object readFile(Map<String, Object> inputs) {
        String rawPath = inputs == null ? "" : asString(inputs.get("file_path"));
        if (rawPath.isBlank()) {
            return failure(null, "file_path is required");
        }
        Path target;
        try {
            target = Paths.get(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return failure(rawPath, "file_path is not a valid path: " + e.getMessage());
        }
        return rejectIfOutsideAllowed(target).orElseGet(() -> statAndRead(target));
    }

    private Optional<Map<String, Object>> rejectIfOutsideAllowed(Path target) {
        if (allowedRoots.isEmpty()) {
            LOG.warn("readFile rejected reason=no_allowed_roots path={}", target);
            return Optional.of(failure(target.toString(),
                    "no allowed roots configured; readFile is disabled"));
        }
        boolean underRoot = allowedRoots.stream().anyMatch(target::startsWith);
        if (!underRoot) {
            LOG.warn("readFile rejected reason=outside_allowed_roots path={}", target);
            return Optional.of(failure(target.toString(),
                    "file_path is outside the allow-listed roots"));
        }
        return Optional.empty();
    }

    private Map<String, Object> statAndRead(Path target) {
        if (!Files.exists(target)) {
            return failure(target.toString(), "file does not exist");
        }
        if (!Files.isRegularFile(target)) {
            return failure(target.toString(), "file_path is not a regular file");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            return failure(target.toString(), "stat failed: " + e.getMessage());
        }
        if (size > MAX_BYTES) {
            LOG.warn("readFile rejected reason=too_large path={} bytes={} max={}",
                    target.getFileName(), size, MAX_BYTES);
            return failure(target.toString(),
                    "file exceeds " + MAX_BYTES + "-byte cap (actual=" + size + ")");
        }
        String content;
        try {
            content = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return failure(target.toString(), "read failed: " + e.getMessage());
        }
        LOG.info("readFile ok path={} bytes={}", target.getFileName(), size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", target.toString());
        result.put("bytes", size);
        result.put("content", content);
        return result;
    }

    private static Map<String, Object> failure(String path, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        if (path != null) {
            result.put("path", path);
        }
        result.put("error", error);
        return result;
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<Path> canonicaliseRoots(List<String> raw) {
        List<Path> canonical = new ArrayList<>();
        if (raw == null) {
            return canonical;
        }
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path p = Paths.get(entry).toAbsolutePath().normalize();
                if (!canonical.contains(p)) {
                    canonical.add(p);
                }
            } catch (InvalidPathException e) {
                LOG.warn("skill_readFile_rail skip invalid root entry={} reason={}",
                        entry, e.getMessage());
            }
        }
        return canonical;
    }
}
