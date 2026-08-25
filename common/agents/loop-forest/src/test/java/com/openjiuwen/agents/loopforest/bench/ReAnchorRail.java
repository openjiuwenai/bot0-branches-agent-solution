/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.bench;

import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B 臂重锚 rail——Python goalrail GoalRailLite（CHECK 模式）的 Java 移植。
 *
 * <p>三件套检查（确定性，零 LLM）+ 重锚后果（Python v2 母矩阵 B−A=+19pp 的机制）：
 * <ul>
 *   <li>schema_check / distance_to_done：主工件 required 字段缺失计数</li>
 *   <li>drift_flag：自上次检查以来的新消息文本命中漂移词元表（通道盲——只扫文本；
 *       跳过重锚消息自身防自触发）</li>
 *   <li>drift_fire（B 臂语义：词元命中即触发，无签名节点要求）→ pushSteering 重锚消息</li>
 * </ul>
 *
 * <p>重锚消息三要素（Python _reanchor 同构）：OUT-OF-SIGNATURE 标记 +
 * distance_to_done 实数 + "out-of-signature content does NOT count toward the
 * contract; finish signature items first" 指令。
 *
 * <p>与 VETO 静默模式的关系（R2-fix2 教训）：B 臂消息不致瘫——v2 母矩阵 B 臂
 * flash 64% SAT 实证（对照 VETO 臂信号致瘫 0% 的历史坑）。
 *
 * @since 2026-08
 */
class ReAnchorRail extends AgentRail {

    private final Path artifactDir;
    private final String taskId;
    private final List<String> requiredFields;
    private final List<String> driftLexicon;

    private int lastSeenMsgCount;
    private int reanchorEvents;
    private int driftEvents;
    private int signalEvents;

    /**
     * 构造 B 臂重锚 rail。
     *
     * @param artifactDir    工件目录（含 out/）
     * @param taskId         任务标识（重锚消息锚定用）
     * @param requiredFields 主工件必填字段（distance_to_done 计数基准）
     * @param driftLexicon   漂移词元表（小写子串匹配，通道盲）
     */
    ReAnchorRail(Path artifactDir, String taskId, List<String> requiredFields,
            List<String> driftLexicon) {
        this.artifactDir = artifactDir;
        this.taskId = taskId;
        this.requiredFields = List.copyOf(requiredFields);
        this.driftLexicon = driftLexicon.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        List<Object> msgs = inputs.getMessages();
        // (c) drift_flag：自上次检查以来的新消息（通道盲文本扫描；跳过信号消息自身）
        List<String> hits = new ArrayList<>();
        for (int i = lastSeenMsgCount; i < msgs.size(); i++) {
            String text = contentText(msgs.get(i)).toLowerCase(Locale.ROOT);
            if (text.contains("[GOAL SIGNAL") || text.contains("OUT-OF-SIGNATURE")) {
                continue; // 信号/重锚消息自身不参与（防自触发风暴）
            }
            for (String tok : driftLexicon) {
                if (!tok.isBlank() && text.contains(tok)) {
                    hits.add(tok);
                }
            }
        }
        lastSeenMsgCount = msgs.size();
        List<String> missing = missingFields();
        int distance = missing.size();
        // B 臂（CHECK 模式）完整语义（Python GoalRailLite）：每轮发 goal_signal——
        // 含 schema_violations 字段清单（Python 载荷消息面：模型知道"缺什么"
        // 才知道"写什么"——只报数字不足以驱动写工件）
        ctx.pushSteering(goalSignalMessage(missing, hits));
        signalEvents++;
        if (!hits.isEmpty()) {
            driftEvents++;
            ctx.pushSteering(reanchorMessage(distance, hits));
            reanchorEvents++;
        }
    }

    /**
     * 每轮 goal_signal（轻量状态——Python GoalSignal.to_payload 的消息面形态）。
     *
     * @param distance 缺失签名字段数
     * @param hits     本轮漂移命中（可为空）
     * @return steering 消息文本
     */
    private String goalSignalMessage(List<String> missing, List<String> hits) {
        return "[GOAL SIGNAL] " + missing.size() + " of " + requiredFields.size()
                + " signature fields still missing; drift: "
                + (hits.isEmpty() ? "none" : hits.size() + " hit(s)")
                + ". ACTION REQUIRED: write these fields to out/v2a5.json NOW "
                + "using write_artifact (draft first if unsure — a partial "
                + "artifact scores partial credit): "
                + String.join(", ", missing);
    }

    /**
     * distance_to_done：主工件 required 字段缺失计数（工件缺失=全部缺失）。
     *
     * @return 缺失字段数
     */
    private List<String> missingFields() {
        try {
            Path art = artifactDir.resolve("out/v2a5.json");
            if (!Files.isRegularFile(art)) {
                return requiredFields;
            }
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(art, StandardCharsets.UTF_8));
            List<String> missing = new ArrayList<>();
            for (String field : requiredFields) {
                if (!resolveField(root, field)) {
                    missing.add(field);
                }
            }
            return missing;
        } catch (Exception e) {
            return requiredFields;
        }
    }

    private int distanceToDone() {
        return missingFields().size();
    }

    /** 字段解析：a.b 形式下钻；joint_window.start 等嵌套字段。 */
    private static boolean resolveField(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root;
        for (String part : field.split("\\.")) {
            if (!node.isObject() || !node.has(part)) {
                return false;
            }
            node = node.path(part);
        }
        return !node.isNull();
    }

    /**
     * 重锚消息（Python _reanchor 载荷的消息面形态）。
     *
     * @param distance 缺失签名字段数
     * @param hits     命中词元
     * @return steering 消息文本
     */
    private String reanchorMessage(int distance, List<String> hits) {
        return "[GOAL RE-ANCHOR] OUT-OF-SIGNATURE marker on task " + taskId + "\n"
                + "distance_to_done: " + distance + " of " + requiredFields.size()
                + " signature fields still missing\n"
                + "drift detected: " + hits.size() + " hit(s): "
                + String.join(", ", hits) + "\n"
                + "Note: out-of-signature content detected; it does NOT count toward "
                + "the contract. Finish signature items first; do not start them.";
    }

    private static String contentText(Object m) {
        if (m instanceof BaseMessage bm && bm.getContent() != null) {
            return String.valueOf(bm.getContent());
        }
        return String.valueOf(m);
    }

    /** 重锚触发计数（实验遥测）。 */
    int reanchorEvents() {
        return reanchorEvents;
    }

    /** 漂移命中轮数（实验遥测）。 */
    int driftEvents() {
        return driftEvents;
    }

    /** 每轮信号发送计数（实验遥测）。 */
    int signalEvents() {
        return signalEvents;
    }
}
