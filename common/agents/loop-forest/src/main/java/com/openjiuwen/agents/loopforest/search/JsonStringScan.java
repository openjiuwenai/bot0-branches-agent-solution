/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

/**
 * 极简 JSON 字符串扫描器——线性单遍、字符串感知、零 JSON 库依赖。
 *
 * <p>4-lens 审查（Lens A）实证：正则 {@code (?:[^"\\]|\\.)*} 是递归匹配，
 * content ≥ ~2000 字符即 StackOverflowError（Error 穿透 catch(Exception)，
 * agent 线程直接炸）；且跨对象 lazy 匹配可错配 title/content。本扫描器逐字符
 * 状态机读取——无递归、无回溯、O(n)，外部网页内容长度不再构成风险。
 *
 * @since 2026-08
 */
final class JsonStringScan {

    private JsonStringScan() {
    }

    /** 解码结果：值文本 + 闭引号索引。 */
    record Decoded(String value, int endQuoteIdx) {
    }

    /**
     * 解码从 openIdx（开引号）开始的 JSON 字符串字面量（含 Unicode 四位十六进制转义）。
     *
     * @param s       JSON 文本
     * @param openIdx 开引号索引
     * @return 解码结果；未闭合或非法转义返回 null
     */
    static Decoded decode(String s, int openIdx) {
        StringBuilder sb = new StringBuilder();
        for (int j = openIdx + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '"') {
                return new Decoded(sb.toString(), j);
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (j + 1 >= s.length()) {
                return null; // 转义悬挂
            }
            char e = s.charAt(++j);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (j + 4 >= s.length()) {
                        return null;
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(j + 1, j + 5), 16));
                        j += 4;
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
                default -> {
                    return null; // 非法转义
                }
            }
        }
        return null; // 未闭合
    }

    /**
     * 找首个 {@code "key":<ws>"value"} 形态的 value（任意深度，首个命中）。
     *
     * @param json JSON 文本
     * @param key  键名
     * @return 解码值；找不到返回 null
     */
    static String stringValueOf(String json, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int ki = json.indexOf(needle, from);
            if (ki < 0) {
                return null;
            }
            int i = skipWs(json, ki + needle.length());
            if (i < json.length() && json.charAt(i) == ':') {
                i = skipWs(json, i + 1);
                if (i < json.length() && json.charAt(i) == '"') {
                    Decoded d = decode(json, i);
                    if (d != null) {
                        return d.value();
                    }
                }
            }
            from = ki + needle.length(); // 此命中非键值形态，继续
        }
        return null;
    }

    /**
     * 从 from（指向 '{'）找对象闭括号（字符串感知——值内大括号不干扰）。
     *
     * @param s       JSON 文本
     * @param openIdx 左大括号索引
     * @return 闭大括号索引；未闭合返回 -1
     */
    static int objectEnd(String s, int openIdx) {
        int depth = 0;
        boolean inString = false;
        for (int j = openIdx; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inString) {
                if (c == '\\') {
                    j++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    /**
     * 跳过空白。
     *
     * @param s JSON 文本
     * @param i 起始索引
     * @return 首个非空白字符索引（可能 == s.length()）
     */
    static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
