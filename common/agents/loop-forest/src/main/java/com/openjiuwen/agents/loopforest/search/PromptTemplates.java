/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * 模型可见文本模板加载器——MR !66 治本范式的通用件（Phase 1 四代进化终审产物）。
 *
 * <p>纪律（终审裁决书"宿主代码最小交付"节）：
 * <ul>
 *   <li>模板全部外置 {@code src/main/resources/prompts/*.txt}，命名占位符 + String.replace 链，
 *       不引模板引擎，不用 .properties（前导空格坑）</li>
 *   <li>渲染后断言最终模型面无字面 {@code {}（漏替换即 fail-loud，不留占位符给模型）</li>
 *   <li>缺文件启动期 fail-loud；占位符缺值 fallback 短句 + log（不静默）</li>
 *   <li>{@link Clock} 可注入——年月/日期运行期解析，拒绝静态/打包期固定</li>
 * </ul>
 *
 * @since 2026-08
 */
public final class PromptTemplates {

    private static final System.Logger LOG = System.getLogger(PromptTemplates.class.getName());

    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);

    /** 模板占位符形状（只用于扫原始模板声明，不扫替换值）。 */
    private static final java.util.regex.Pattern PLACEHOLDER_SHAPE =
            java.util.regex.Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9]*)\\}");

    private final Clock clock;

    /**
     * 构造（默认系统时钟）。
     */
    public PromptTemplates() {
        this(Clock.systemDefaultZone());
    }

    /**
     * 构造。
     *
     * @param clock 时钟（可注入测试用固定钟——运行期解析日期，拒绝静态）
     */
    public PromptTemplates(Clock clock) {
        this.clock = clock;
    }

    /**
     * 加载并渲染模板。
     *
     * <p>断言语义：已声明的占位符必须全部被替换（fail-loud）——但不检查裸大括号，
     * 因为替换值（网页 excerpt/标题）可合法含大括号（代码片段等）。
     *
     * @param name         资源名（相对 prompts/，如 "web-search-description.txt"）
     * @param placeholders 占位符 → 值（命名占位符，如 maxResults → "3"）
     * @return 渲染后文本
     * @throws IllegalStateException 资源缺失或占位符漏替换（启动期 fail-loud）
     */
    public String render(String name, Map<String, String> placeholders) {
        String raw = load(name);
        // 模板侧完整性：模板声明的占位符必须全部由调用方供值（fail-loud，
        // 只扫原始模板——替换值里的合法大括号不参与判定）
        java.util.regex.Matcher declared = PLACEHOLDER_SHAPE.matcher(raw);
        while (declared.find()) {
            if (!placeholders.containsKey(declared.group(1))) {
                throw new IllegalStateException("template " + name
                        + " declares {" + declared.group(1) + "} but caller provides no value");
            }
        }
        String out = raw;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String value = e.getValue() != null ? e.getValue() : "";
            if (e.getValue() == null) {
                LOG.log(System.Logger.Level.WARNING,
                        "placeholder {0} missing value in {1} — rendered empty",
                        e.getKey(), name);
            }
            out = out.replace("{" + e.getKey() + "}", value);
        }
        for (String key : placeholders.keySet()) {
            if (out.contains("{" + key + "}")) {
                throw new IllegalStateException(
                        "unreplaced placeholder {" + key + "} remains in " + name);
            }
        }
        return out;
    }

    /**
     * 当前年月（英文格式，Claude Code currentMonthYear 同构——运行期解析）。
     *
     * @return 如 "August 2026"
     */
    public String currentMonthYear() {
        return MONTH_YEAR.format(LocalDate.now(clock));
    }

    /**
     * 当前日期（ISO，锚定终止符用）。
     *
     * @return 如 "2026-08-24"
     */
    public String currentDate() {
        return DATE.format(LocalDate.now(clock));
    }

    private static String load(String name) {
        String path = "/prompts/" + name;
        try (InputStream in = PromptTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("prompt resource missing: " + path + " — 真源不可缺");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load " + path, e);
        }
    }
}
