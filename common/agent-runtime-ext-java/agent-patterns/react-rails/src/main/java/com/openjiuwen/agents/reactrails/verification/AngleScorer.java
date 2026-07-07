/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则式角度评分器 — 零 LLM 成本，用于 F-widen-then-converge 发散阶段。
 *
 * <p>将 LLM 输出按编号格式拆分为候选角度，每角度 3 维度评分（清晰度/深度/相关性），
 * 按综合评分降序返回。阈值由 {@link WidenThenConvergeRail} 定义。
 *
 * <p>评分是纯规则式的，不依赖任何 LLM 调用：
 * <ul>
 *   <li><b>清晰度 clarity</b>：首行主题句长度（>15字有力，>8字一般，<8字无主题句）</li>
 *   <li><b>深度 depth</b>：中文字数 + 数字/数据出现 + 因果推理关键词</li>
 *   <li><b>相关性 relevance</b>：successCriteria 关键词在 section 中的命中率</li>
 * </ul>
 */
public class AngleScorer {

    /** 角度拆分正则：匹配行首 "1. "、"2. " 等编号前缀。 */
    private static final Pattern SECTION_SPLIT =
            Pattern.compile("(?m)(?=^\\d+\\.\\s)|(?=^角度[一二三123][：:.、\\s])");

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "和", "就", "不", "都", "一", "一个",
            "上", "也", "有", "到", "与", "其", "中", "对", "为", "此");

    /**
     * 已评分角度记录。
     *
     * @param label     角度标签（首行/标题，去编号前缀）
     * @param content   角度完整内容
     * @param clarity   清晰度 1-5
     * @param depth     深度 1-5
     * @param relevance 相关性 1-5
     */
    public record ScoredAngle(String label, String content,
                               double clarity, double depth, double relevance) {
        public double average() {
            return (clarity + depth + relevance) / 3.0;
        }
    }

    /**
     * 从 LLM 输出中提取并评分多个角度。
     *
     * @param output   LLM 最终回答文本
     * @param criteria 成功标准列表（用于相关性评分）
     * @return 按 avg 降序排列的角度列表；无法解析时返回空列表
     */
    public List<ScoredAngle> extractAngles(String output, List<String> criteria) {
        if (output == null || output.isBlank()) {
            return List.of();
        }

        String[] rawSections = SECTION_SPLIT.split(output);
        List<ScoredAngle> angles = new ArrayList<>();

        for (String raw : rawSections) {
            String section = raw.trim();
            // 过滤噪音片段：太短的内容不可能是独立角度
            if (section.length() < 30) {
                continue;
            }

            String label = extractLabel(section);
            double clarity = scoreClarity(section);
            double depth = scoreDepth(section);
            double relevance = scoreRelevance(section, criteria);

            angles.add(new ScoredAngle(label, section, clarity, depth, relevance));
        }

        // 按 avg 降序
        angles.sort((a, b) -> Double.compare(b.average(), a.average()));
        return angles;
    }

    // ---- 内部评分方法 ----

    /** 提取角度标签：首行前 40 字符，去编号前缀。 */
    static String extractLabel(String section) {
        String[] lines = section.split("[\\r\\n]", 2);
        String first = lines[0].trim();
        // 去掉编号前缀 "1. " 等
        String clean = first.replaceFirst("^\\d+\\.\\s*", "");
        if (clean.length() > 40) {
            return clean.substring(0, 37) + "...";
        }
        return clean;
    }

    /**
     * 清晰度评分：主题句是否明确。
     *
     * <p>首行作为主题句。如果首行 >15 字且有分析内容 → 4.0 分。
     * >8 字但有内容 → 3.0 分。短标题或无分析 → 1.0-1.5 分。
     */
    static double scoreClarity(String section) {
        String[] lines = section.split("[\\r\\n]+");
        if (lines.length < 2) {
            return 1.0;
        }
        String firstLine = lines[0].trim().replaceFirst("^\\d+\\.\\s*", "");
        // 主题句 >15 字且不是纯标题（无句号/冒号结尾的短句视为标题而非分析）
        if (firstLine.length() > 15) {
            return 4.0;
        } else if (firstLine.length() > 8) {
            return 3.0;
        }
        return 1.5;
    }

    /**
     * 深度评分：中文字数 + 数据/因果/举例信号。
     *
     * <ul>
     *   <li>中文 >40 字 +0.5</li>
     *   <li>中文 >80 字 +0.5</li>
     *   <li>中文 >150 字 +0.5</li>
     *   <li>含数字/百分比 +0.75</li>
     *   <li>含因果推理关键词 +0.75</li>
     * </ul>
     */
    static double scoreDepth(String section) {
        int chineseChars = section.replaceAll("[^\\u4e00-\\u9fff]", "").length();
        boolean hasNumbers = section.matches(".*\\d+[.%％]?.*");
        boolean hasReasoning = section.contains("因为") || section.contains("所以")
                || section.contains("例如") || section.contains("导致")
                || section.contains("这意味着") || section.contains("具体来看");

        double score = 1.0;
        if (chineseChars > 40)  { score += 0.5; }
        if (chineseChars > 80)  { score += 0.5; }
        if (chineseChars > 150) { score += 0.5; }
        if (hasNumbers)         { score += 0.75; }
        if (hasReasoning)       { score += 0.75; }
        return Math.min(score, 5.0);
    }

    /**
     * 相关性评分：关键命中 / successCriteria 关键词。
     *
     * <p>对每条 criterion 提取非停用词关键词，计算命中比例，线性映射到 [1.0, 5.0]。
     */
    static double scoreRelevance(String section, List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return 3.0; // 无 criteria 时中性分
        }
        String lowerSection = section.toLowerCase(Locale.ROOT);
        long totalKeywords = 0;
        long matchedKeywords = 0;

        for (String criterion : criteria) {
            if (criterion == null || criterion.isBlank()) continue;
            String[] words = criterion.toLowerCase(Locale.ROOT)
                    .split("[\\s,，、。.：:]+");
            for (String w : words) {
                if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                    totalKeywords++;
                    if (lowerSection.contains(w)) {
                        matchedKeywords++;
                    }
                }
            }
        }

        if (totalKeywords == 0) return 3.0;
        double ratio = (double) matchedKeywords / totalKeywords;
        return 1.0 + ratio * 4.0; // [1.0, 5.0] 线性映射
    }
}
