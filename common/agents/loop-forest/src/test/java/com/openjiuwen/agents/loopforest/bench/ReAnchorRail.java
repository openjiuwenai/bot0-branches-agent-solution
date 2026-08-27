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

    /** 死胡同组合（契约 deadend_fingerprint.registry 单条——三角色文件集合等值匹配）。 */
    record DeadendCombo(String label, String baseline, String intervention, String followup) {}

    private final Path artifactDir;
    private final String taskId;
    private final List<String> requiredFields;
    private final List<String> driftLexicon;
    /** C 臂死胡同表（null = B 臂纯语义——S1/S2 整段跳过，单因子保证）。 */
    private final List<DeadendCombo> combos;
    /** C1 墓碑开关（C0/C1 唯一差异）。 */
    private final boolean tombstoneOn;

    private int lastSeenMsgCount;
    private int reanchorEvents;
    private int driftEvents;
    private int signalEvents;
    private int rollbackEvents;
    private int tombstoneEvents;
    private int deadendRounds;
    /** S1 签名检查点：最后干净轮的消息<b>内容快照</b>（内容寻址——4-lens S2-FID-02：
     * 绝对索引在 BenchContextRail 先行逐出/去重下跨轮漂移，错位截断泄漏死胡同尾部）。 */
    private List<Object> checkpointSnapshot;
    /** 回滚活跃态：脏窗口内每轮重截断（4-lens S2L-01/BRG-001 治本——per-call 拷贝
     * 单次截断不落窗，ContextWindow 全量召回；状态化重截断=失忆语义的消息面等价）。 */
    private boolean rollbackActive;
    /** 最近死胡同命中（cap 后墓碑保活用）。 */
    private DeadendCombo lastHit;
    /** 真截断次数（msgs 实际被替换才计——4-lens BRG-003/S2L-08：与 rollbackAttempts 分列）。 */
    private int truncationEvents;
    /** 曾满足字段集（契约回归检测基准——Python _regression_violations 同构）。 */
    private final java.util.Set<String> everSatisfied = new java.util.HashSet<>();
    /** Python 为种子随机 {2,3}；Java 固定取 2（分布下支，两 C 臂同值保单因子）。 */
    private static final int ROLLBACK_CAP = 2;

    /**
     * 构造 B 臂重锚 rail（纯 B 语义，无 S1/S2）。
     *
     * @param artifactDir    工件目录（含 out/）
     * @param taskId         任务标识（重锚消息锚定用）
     * @param requiredFields 主工件必填字段（distance_to_done 计数基准）
     * @param driftLexicon   漂移词元表（小写子串匹配，通道盲）
     */
    ReAnchorRail(Path artifactDir, String taskId, List<String> requiredFields,
            List<String> driftLexicon) {
        this(artifactDir, taskId, requiredFields, driftLexicon, null, false);
    }

    /**
     * 构造 C 臂 rail（B + S1 签名检查点 + S2 回滚）。
     *
     * @param combos      死胡同组合表（非 null 即启用 S1/S2）
     * @param tombstoneOn C1=true 回滚后注入墓碑；C0=false 裸截断（无记忆——
     *                    复刻 Python address_plus_replay 的结构性失忆）
     */
    ReAnchorRail(Path artifactDir, String taskId, List<String> requiredFields,
            List<String> driftLexicon, List<DeadendCombo> combos, boolean tombstoneOn) {
        this.artifactDir = artifactDir;
        this.taskId = taskId;
        this.requiredFields = List.copyOf(requiredFields);
        this.driftLexicon = driftLexicon.stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.combos = combos == null ? null : List.copyOf(combos);
        this.tombstoneOn = tombstoneOn;
    }

    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        List<Object> msgs = inputs.getMessages();
        // ── S1/S2（C 臂；B 臂 combos=null 整段跳过）──
        if (combos != null) {
            DeadendCombo hit = deadendHit();
            boolean regression = regressionHit();
            boolean dirty = hit != null || regression;
            if (dirty) {
                deadendRounds++;
            }
            if (rollbackEvents < ROLLBACK_CAP) {
                if (dirty) {
                    // S2 回滚（4-lens 修复版）：重截断至检查点<b>内容快照</b>。
                    // 框架的 ModelCallInputs.messages 是每调用新建拷贝且无回写
                    // ContextWindow 通道——单次截断下一轮即被全量召回（结构性
                    // 失忆不可达）。故脏窗口内每轮重截断：每轮 prompt 恒为检查点
                    // 前缀=Python store 手术的消息面等价。
                    if (checkpointSnapshot == null) {
                        checkpointSnapshot = new ArrayList<>(msgs); // 首轮即脏：以当前为检查点
                    } else if (msgs.size() != checkpointSnapshot.size()
                            || !msgs.equals(checkpointSnapshot)) {
                        msgs.clear();
                        msgs.addAll(checkpointSnapshot);
                        truncationEvents++;
                    }
                    rollbackEvents++;
                    rollbackActive = true;
                    if (hit != null) {
                        lastHit = hit;
                    }
                    if (tombstoneOn && hit != null) {
                        // 墓碑直接 append 到当轮 prompt（4-lens S2-FID-01 修复：
                        // pushSteering 要到下一迭代顶才 drain，且注入位置在
                        // 检查点之后会被下一次截断删除——pre-cap 永不可见）
                        msgs.add(new com.openjiuwen.core.foundation.llm.schema
                                .UserMessage(tombstoneMessage(hit)));
                        tombstoneEvents++;
                    } else if (tombstoneOn && regression) {
                        // 契约回归变体墓碑（4-lens S2-FID-04：预注册 §3.1 触发
                        // 含契约回归——回归无 combo 可指，按缺失字段拼装）
                        msgs.add(new com.openjiuwen.core.foundation.llm.schema
                                .UserMessage(regressionTombstoneMessage()));
                        tombstoneEvents++;
                    }
                    // 观测探针（4-lens S2-LAUNCH-02）：截断后视野内死胡同文件名
                    // 计数——C0 期望 0（截断干净=失忆验证的数据源）；C1 含墓碑自带
                    // 文件名 ≥1。冒烟 gate 2 按此行 grep 判定。
                    int deadendTokens = 0;
                    for (Object m : msgs) {
                        String t = contentText(m);
                        if (t.contains(hit != null ? hit.baseline() : lastHit != null
                                ? lastHit.baseline() : "%%NO-COMBO%%")) {
                            deadendTokens++;
                        }
                    }
                    System.out.println("[s2] rollback#" + rollbackEvents
                            + (hit != null ? " deadend=" + hit.label() : " regression")
                            + " msgs " + msgs.size() + " (checkpoint="
                            + checkpointSnapshot.size() + ")"
                            + " deadend_tokens_in_view=" + deadendTokens
                            + (tombstoneOn && hit != null ? " tombstone="
                                    + tombstoneMessage(hit).length() + "chars" : ""));
                } else if (rollbackActive) {
                    // 脏转净：确立新检查点。S2R2-02 勘误：墓碑只 append 到
                    // per-call 拷贝、从不进 ModelContext——转净后即蒸发，检查点
                    // 与 C0 内容相同（墓碑效应窗口=脏轮 only，见预注册 v1.2）。
                    checkpointSnapshot = new ArrayList<>(msgs);
                    rollbackActive = false;
                } else {
                    checkpointSnapshot = new ArrayList<>(msgs);
                }
                if (!dirty) {
                    everSatisfied.addAll(satisfiedFields());
                }
            } else {
                // cap 后（4-lens S2-FID-03 修复）：dirty 时无条件否定性重锚——
                // Python 降级重锚带 OUT-OF-SIGNATURE 标记；死胡同完备形态
                // distance=0，仅靠 goal_signal 读作"已完成"会诱导死胡同收工
                if (dirty) {
                    ctx.pushSteering(capReanchorMessage(hit, regression));
                    if (tombstoneOn) {
                        // 墓碑保活（S2R2-03 对称化：regression-only 也保活——
                        // 与 pre-cap 分支的变体墓碑同构）
                        msgs.add(new com.openjiuwen.core.foundation.llm.schema.UserMessage(
                                hit != null ? tombstoneMessage(hit)
                                        : regressionTombstoneMessage()));
                        tombstoneEvents++;
                    }
                }
                rollbackActive = false;
            }
            // 截断后修正 drift 扫描基线（防 lastSeenMsgCount > msgs.size() 漏扫）
            lastSeenMsgCount = Math.min(lastSeenMsgCount, msgs.size());
        }
        // (c) drift_flag：自上次检查以来的新消息（通道盲文本扫描；跳过信号消息自身）
        List<String> hits = new ArrayList<>();
        for (int i = lastSeenMsgCount; i < msgs.size(); i++) {
            String text = contentText(msgs.get(i)).toLowerCase(Locale.ROOT);
            // T2 修复（4-lens R1）：needle 小写化对齐 haystack——旧版混合大小写
            // needle 对 toLowerCase 后的 haystack 恒 false，守卫为死代码
            if (text.contains("[goal signal") || text.contains("out-of-signature")) {
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
     * <p>必修①（S2-PREREG §5）：字段清单以<b>嵌套示意</b>呈现（baseline{file,rows}
     * 形态）——旧版平铺点路径（"baseline.file, baseline.rows"）被模型照抄为顶层
     * 键名，扁平 JSON 对判分器嵌套期望 → CA1.1 误形态。
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
                + "using write_artifact as NESTED json (do NOT copy dotted names "
                + "as top-level keys — nest them: "
                + nestedHint(missing) + ")";
    }

    /** 缺失字段的嵌套示意（分组下钻：baseline.file+baseline.rows → baseline{file,rows}）。 */
    private static String nestedHint(List<String> missing) {
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (String f : missing) {
            int dot = f.indexOf('.');
            if (dot < 0) {
                groups.computeIfAbsent(f, x -> new ArrayList<>()).add("%%NO-COMBO%%");
            } else {
                groups.computeIfAbsent(f.substring(0, dot), x -> new ArrayList<>())
                        .add(f.substring(dot + 1));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            boolean topOnly = e.getValue().equals(List.of("%%NO-COMBO%%"));
            if (topOnly) {
                sb.append(e.getKey());
            } else {
                sb.append(e.getKey()).append("{")
                        .append(String.join(",", e.getValue())).append("}");
            }
        }
        return sb.toString();
    }

    /**
     * S2 墓碑消息（C1）：确定性模板，零 LLM 生成——C0/C1 单因子保证。
     * 硬约束 ≤50 token（S2-PREREG §3.1）。
     * <p>4-lens S2L-02 修复：模板<b>不含 combo label</b>——registry label
     * （certified_combo_*）命中漂移词元 "certified"，会给 C1 注入独有 reanchor
     * 污染单因子。三文件名与固定词面已对漂移词元表核验零交集。
     */
    private static String tombstoneMessage(DeadendCombo c) {
        return "[TOMBSTONE] rolled back: already tried "
                + c.baseline() + " + " + c.intervention() + " + " + c.followup()
                + " together — that assembly fails the constraints."
                + " Do NOT repeat these three files; pick different ones.";
    }

    /** 契约回归变体墓碑（S2-FID-04）：无 combo 可指，按曾满足字段拼装。 */
    private String regressionTombstoneMessage() {
        java.util.Set<String> lost = new java.util.HashSet<>(everSatisfied);
        lost.removeAll(satisfiedFields());
        return "[TOMBSTONE] rolled back: fields " + lost
                + " were satisfied before but are now missing."
                + " Restore them in out/v2a5.json.";
    }

    /** cap 后否定性重锚（S2-FID-03：死胡同完备形态 distance=0 需显式否定）。 */
    private String capReanchorMessage(DeadendCombo hit, boolean regression) {
        return "[GOAL RE-ANCHOR] OUT-OF-SIGNATURE marker on task " + taskId + "\n"
                + "current artifact state does NOT count toward the contract"
                + (hit != null ? " (forbidden file assembly still present)" : "")
                + (regression ? " (previously satisfied fields regressed)" : "")
                + ". Change the file combination / restore fields; do not finish yet.";
    }

    /**
     * 死胡同命中检测：out/v2a5.json 与 out/v2a5-draft.json 双路径扫描（Python
     * goalrail L247 同构——draft 残留持续触发是 L4-03 语义，被测机制如实保留）。
     *
     * @return 命中的组合；无命中 null
     */
    private DeadendCombo deadendHit() {
        for (String name : new String[] {"out/v2a5.json", "out/v2a5-draft.json"}) {
            DeadendCombo hit = matchCombo(artifactDir.resolve(name));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private DeadendCombo matchCombo(Path artifact) {
        try {
            if (!Files.isRegularFile(artifact)) {
                return null;
            }
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(artifact, StandardCharsets.UTF_8));
            String b = root.path("baseline").path("file").asText(null);
            String i = root.path("intervention").path("file").asText(null);
            String f = root.path("followup").path("file").asText(null);
            if (b == null || i == null || f == null) {
                return null;
            }
            for (DeadendCombo c : combos) {
                if (c.baseline().equals(b) && c.intervention().equals(i)
                        && c.followup().equals(f)) {
                    return c;
                }
            }
        } catch (Exception e) {
            return null; // 不可解析工件不判死胡同（诚实边界）
        }
        return null;
    }

    /** 契约回归：曾满足字段现在缺失（终稿路径，Python _regression_violations 同构）。 */
    private boolean regressionHit() {
        if (everSatisfied.isEmpty()) {
            return false;
        }
        return !satisfiedFields().containsAll(everSatisfied);
    }

    /** 当前终稿已满足的必填字段集。 */
    private java.util.Set<String> satisfiedFields() {
        java.util.Set<String> ok = new java.util.HashSet<>();
        try {
            Path art = artifactDir.resolve("out/v2a5.json");
            if (!Files.isRegularFile(art)) {
                return ok;
            }
            var root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(art, StandardCharsets.UTF_8));
            for (String field : requiredFields) {
                if (resolveField(root, field)) {
                    ok.add(field);
                }
            }
        } catch (Exception e) {
            return ok; // 读失败=零满足（保守）
        }
        return ok;
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

    /** S2 回滚计数（C 臂实验遥测；真截断见 {@link #truncationEvents()}）。 */
    int rollbackEvents() {
        return rollbackEvents;
    }

    /** 真截断次数（msgs 实际被替换——BRG-003：与 rollbackAttempts 分列）。 */
    int truncationEvents() {
        return truncationEvents;
    }

    /** 墓碑注入计数（C1 实验遥测）。 */
    int tombstoneEvents() {
        return tombstoneEvents;
    }

    /** 死胡同/回归命中轮数（C 臂实验遥测）。 */
    int deadendRounds() {
        return deadendRounds;
    }
}
