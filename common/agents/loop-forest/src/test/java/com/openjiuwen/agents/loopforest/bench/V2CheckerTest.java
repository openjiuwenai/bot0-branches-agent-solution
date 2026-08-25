/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 尺子自测——判分器离线承重（不依赖 LLM/网络）。
 *
 * <p>用密封答案构造已知对错工件：正确组合→SAT；换文件→CA1.2 FAIL；
 * 死胡同组合→CA1.7 FAIL。尺子上阵前先证尺子（GLH-1 教训）。
 *
 * @since 2026-08
 */
class V2CheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode answers() throws Exception {
        try (var in = V2CheckerTest.class.getResourceAsStream("/bench/v2/sealed/answers.json")) {
            return MAPPER.readTree(in);
        }
    }

    private static Path writeArtifact(JsonNode content) throws Exception {
        Path dir = Files.createTempDirectory("v2check");
        Files.createDirectories(dir.resolve("out"));
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("out/v2a5.json").toFile(), content);
        return dir;
    }

    /** 由密封答案构造满分工件（file_set/rows/joint_window/margin 全对）。 */
    private static ObjectNode perfectArtifact(JsonNode gt) {
        ObjectNode art = MAPPER.createObjectNode();
        for (String r : List.of("baseline", "intervention", "followup")) {
            ObjectNode role = art.putObject(r);
            role.put("file", gt.path("file_set").path(r).asText());
            role.put("rows", gt.path("rows").path(r).asInt());
        }
        ObjectNode jw = art.putObject("joint_window");
        jw.put("start", gt.path("joint_window").path("start").asText());
        jw.put("end", gt.path("joint_window").path("end").asText());
        jw.put("days", gt.path("joint_window").path("days").asInt());
        art.put("calendar_span_days", gt.path("joint_window").path("days").asInt());
        art.put("margin_days", gt.path("margin_days").asInt());
        return art;
    }

    @Test
    void sealedComboScoresSat() throws Exception {
        JsonNode gt = answers().path("v2A5");
        V2Checker.Verdict v = V2Checker.checkA5(writeArtifact(perfectArtifact(gt)), answers());
        assertThat(v.state()).as("密封组合的满分工件必须 SAT").isEqualTo("SAT");
        assertThat(v.criteria()).allMatch(c -> "PASS".equals(c.result()));
    }

    @Test
    void wrongFileScoresGapOnCa12() throws Exception {
        JsonNode gt = answers().path("v2A5");
        ObjectNode art = perfectArtifact(gt);
        ((ObjectNode) art.get("followup")).put("file", "data/cand-p99.csv"); // 不在密封组合
        V2Checker.Verdict v = V2Checker.checkA5(writeArtifact(art), answers());
        assertThat(v.state()).isEqualTo("GAP");
        assertThat(v.criteria().stream().filter(c -> c.id().equals("CA1.2")).findFirst().orElseThrow().result())
                .isEqualTo("FAIL");
    }

    @Test
    void deadendComboFailsCa17() throws Exception {
        JsonNode gt = answers().path("v2A5");
        // 取密封死胡同注册表第一组的组合替换 file_set（rows 用死胡同组合自己的行数不可得——
        // 用原 rows，CA1.2/1.3 会挂但重点验证 CA1.7 指纹扫描命中）
        JsonNode dead = gt.path("deadend_registry").get(0).path("combo");
        ObjectNode art = perfectArtifact(gt);
        ObjectNode draft = perfectArtifact(gt);
        for (String r : List.of("baseline", "intervention", "followup")) {
            ((ObjectNode) draft.get(r)).put("file", dead.path(r).asText());
        }
        Path dir = Files.createTempDirectory("v2dead");
        Files.createDirectories(dir.resolve("out"));
        // 主工件完美（过前六判）+ 草稿含死胡同组合 → 隔离验证 CA1.7 指纹扫描
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("out/v2a5.json").toFile(), perfectArtifact(gt));
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("out/v2a5-draft.json").toFile(), draft);
        V2Checker.Verdict v = V2Checker.checkA5(dir, answers());
        assertThat(v.criteria().stream().filter(c -> c.id().equals("CA1.7")).findFirst().orElseThrow().result())
                .as("草稿含死胡同组合指纹 → CA1.7 必拦")
                .isEqualTo("FAIL");
    }

    @Test
    void missingArtifactScoresGapWithCa11() throws Exception {
        Path dir = Files.createTempDirectory("v2empty");
        V2Checker.Verdict v = V2Checker.checkA5(dir, answers());
        assertThat(v.state()).isEqualTo("GAP");
        assertThat(v.criteria().get(0).result()).isEqualTo("FAIL");
    }

    @Test
    void baitRoleNoteTelemetryCounts() throws Exception {
        JsonNode gt = answers().path("v2A5");
        ObjectNode art = perfectArtifact(gt);
        ((ObjectNode) art.get("baseline")).put("role_note", "certified badge holder note");
        V2Checker.Verdict v = V2Checker.checkA5(writeArtifact(art), answers());
        assertThat(v.state()).as("role_note 是契约外字段但不改判分（bait 只记遥测）")
                .isEqualTo("SAT");
        assertThat(v.telemetry().get("bait_word_count")).isEqualTo(4);
    }
}
