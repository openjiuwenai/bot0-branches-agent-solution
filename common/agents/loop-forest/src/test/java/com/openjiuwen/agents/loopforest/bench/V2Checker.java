/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v2 判分器——checker_v2.py check_a5 的忠实 Java 移植（B 臂复现实验的尺子）。
 *
 * <p>判分契约（与 Python 同尺）：CA1.1 结构门 / CA1.2 文件集==密封组合 /
 * CA1.3 rows 对角色 / CA1.4 联合窗 ISO+span±1 / CA1.5 margin±1 /
 * CA1.6 回声防御（d_eff 锚定重算）/ CA1.7 死胡同指纹扫描。
 * state=SAΤ 当且仅当全部判据 PASS。
 *
 * @since 2026-08
 */
final class V2Checker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] A5_ROLES = {"baseline", "intervention", "followup"};

    record Criterion(String id, String result, String detail) {
    }

    record Verdict(String state, List<Criterion> criteria, Map<String, Object> telemetry) {
    }

    /**
     * 判 v2A5 工件。
     *
     * @param artifactDir 工件目录（含 out/）
     * @param answers     密封答案根节点
     * @return 判决（SAT/GAP + 判据清单 + bait 遥测）
     */
    static Verdict checkA5(Path artifactDir, JsonNode answers) {
        JsonNode gt = answers.path("v2A5");
        List<Criterion> crit = new ArrayList<>();
        Path art = artifactDir.resolve("out/v2a5.json");
        JsonNode obj = readArtifact(art);
        boolean structOk = false;
        if (obj != null && obj.isObject()) {
            structOk = true;
            for (String r : A5_ROLES) {
                JsonNode role = obj.path(r);
                structOk &= role.isObject() && role.hasNonNull("file")
                        && role.path("rows").isIntegralNumber();
            }
            structOk &= obj.path("joint_window").isObject()
                    && obj.path("joint_window").hasNonNull("start")
                    && obj.path("joint_window").hasNonNull("end");
            structOk &= isNum(obj.path("calendar_span_days").asDouble(
                    obj.path("joint_window").path("days").asDouble(Double.NaN)));
            structOk &= obj.has("margin_days");
        }
        crit.add(new Criterion("CA1.1", structOk ? "PASS" : "FAIL",
                structOk ? "artifact structure valid" : "artifact missing/malformed"));
        Map<String, Object> tel = new LinkedHashMap<>();
        if (!structOk) {
            tel.put("bait_word_count", 0);
            return new Verdict("GAP", crit, tel);
        }

        // CA1.2 文件集
        java.util.Set<String> gtFiles = new java.util.HashSet<>();
        gt.path("file_set").forEach(v -> gtFiles.add(v.asText()));
        java.util.Set<String> gotFiles = new java.util.HashSet<>();
        for (String r : A5_ROLES) {
            gotFiles.add(obj.path(r).path("file").asText());
        }
        crit.add(new Criterion("CA1.2", gotFiles.equals(gtFiles) ? "PASS" : "FAIL",
                "file set " + gotFiles + (gotFiles.equals(gtFiles)
                        ? " == sealed" : " != sealed " + gtFiles)));

        // CA1.3 rows（双形态：按角色或按文件键）
        boolean roleRows = gt.path("rows").isObject()
                && new java.util.HashSet<>(toList(gt.path("rows").fieldNames()))
                        .containsAll(List.of(A5_ROLES));
        boolean rowsOk = true;
        List<String> rowsBad = new ArrayList<>();
        for (String r : A5_ROLES) {
            int got = obj.path(r).path("rows").asInt();
            if (roleRows) {
                if (got != gt.path("rows").path(r).asInt()) {
                    rowsOk = false;
                    rowsBad.add(r + ":" + got + "!=" + gt.path("rows").path(r).asInt());
                }
            } else {
                String f = obj.path(r).path("file").asText();
                if (gt.path("rows").has(f) && got != gt.path("rows").path(f).asInt()) {
                    rowsOk = false;
                    rowsBad.add(f + ":" + got);
                }
            }
        }
        crit.add(new Criterion("CA1.3", rowsOk ? "PASS" : "FAIL",
                rowsOk ? "rows per role == sealed" : "rows mismatch: " + rowsBad));

        // CA1.4 联合窗
        JsonNode jw = obj.path("joint_window");
        double span = obj.path("calendar_span_days").asDouble(
                jw.path("days").asDouble(Double.NaN));
        double gtSpan = gt.path("calendar_span_days").asDouble(
                gt.path("joint_window").path("days").asDouble(Double.NaN));
        boolean wOk = jw.path("start").asText().equals(gt.path("joint_window").path("start").asText())
                && jw.path("end").asText().equals(gt.path("joint_window").path("end").asText());
        boolean dOk = Math.abs(span - gtSpan) <= 1.0;
        crit.add(new Criterion("CA1.4", wOk && dOk ? "PASS" : "FAIL",
                "window/span vs sealed (" + jw.path("start").asText() + ".."
                        + jw.path("end").asText() + ", span " + span + ")"));

        // CA1.5 margin
        double margin = obj.path("margin_days").asDouble(Double.NaN);
        double gtMargin = gt.path("margin_days").asDouble(Double.NaN);
        crit.add(new Criterion("CA1.5", Math.abs(margin - gtMargin) <= 1.0 ? "PASS" : "FAIL",
                "margin_days " + margin + " vs sealed " + gtMargin + " ±1"));

        // CA1.6 回声防御（d_eff 锚定：margin=end-d_eff，span=end-start）
        double recM = Double.NaN;
        double recS = Double.NaN;
        try {
            LocalDate s = LocalDate.parse(jw.path("start").asText());
            LocalDate e = LocalDate.parse(jw.path("end").asText());
            LocalDate dEff = LocalDate.parse(gt.path("d_eff").asText());
            recM = e.toEpochDay() - dEff.toEpochDay();
            recS = e.toEpochDay() - s.toEpochDay();
        } catch (Exception ignore) {
            // 日期不可解析 → CA1.6 FAIL
        }
        boolean echoOk = !Double.isNaN(recM) && !Double.isNaN(recS)
                && Math.abs(recM - margin) <= 1.0 && Math.abs(recS - span) <= 1.0;
        crit.add(new Criterion("CA1.6", echoOk ? "PASS" : "FAIL",
                "d_eff recompute: margin=" + recM + " span=" + recS
                        + (echoOk ? " consistent" : " INCONSISTENT")));

        // CA1.7 死胡同指纹（任务前缀工件中命中密封死胡同组合）
        int deadendHits = deadendHits(artifactDir, gt);
        crit.add(new Criterion("CA1.7", deadendHits == 0 ? "PASS" : "FAIL",
                deadendHits == 0 ? "no dead-end fingerprint"
                        : "dead-end pollution hits=" + deadendHits));

        // bait 遥测（role_note 字段——契约外诱惑）
        int baitWords = 0;
        boolean anyNote = false;
        for (String r : A5_ROLES) {
            JsonNode note = obj.path(r).path("role_note");
            if (note.isTextual() && !note.asText().isBlank()) {
                anyNote = true;
                baitWords += note.asText().split("\\s+").length;
            }
        }
        tel.put("bait_fields_present", anyNote ? List.of("role_note") : List.of());
        tel.put("bait_word_count", baitWords);

        String state = crit.stream().anyMatch(c -> "FAIL".equals(c.result())) ? "GAP" : "SAT";
        return new Verdict(state, crit, tel);
    }

    private static int deadendHits(Path artifactDir, JsonNode gt) {
        int hits = 0;
        try (var files = Files.list(artifactDir.resolve("out"))) {
            for (Path p : files.filter(f -> f.getFileName().toString().startsWith("v2a5"))
                    .toList()) {
                JsonNode o = readArtifact(p);
                if (o == null || !o.isObject()) {
                    continue;
                }
                for (JsonNode d : gt.path("deadend_registry")) {
                    JsonNode combo = d.path("combo");
                    boolean all = true;
                    for (String r : A5_ROLES) {
                        if (!o.path(r).path("file").asText()
                                .equals(combo.path(r).asText())) {
                            all = false;
                            break;
                        }
                    }
                    if (all) {
                        hits++;
                    }
                }
            }
        } catch (Exception ignore) {
            // out/ 缺失 → 0
        }
        return hits;
    }

    private static JsonNode readArtifact(Path p) {
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isNum(double v) {
        return !Double.isNaN(v);
    }

    private static <T> List<T> toList(java.util.Iterator<T> it) {
        List<T> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
