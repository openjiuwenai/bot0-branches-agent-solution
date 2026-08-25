/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WikipediaSearchTool 提取回归——钉住"精确匹配（单元素数组）搜不到"bug。
 *
 * <p>根因：旧实现的 indexOf("\",\"") 门卫只在数组含 ≥2 元素时通过——
 * opensearch 精确命中（唯一词条，如 "Python programming language"）返回单元素数组，
 * 整个响应无 "," 序列 → 门卫失败 → 报 "No articles found"。
 *
 * <p>承重：剥掉结构正则（mutation）→ 本测试 RED，证非恒真。
 *
 * @since 2026-08
 */
class WikipediaSearchToolTest {

    // opensearch 真实响应样本（2026-08-23 实测）：多元素（模糊命中）
    private static final String MULTI_TITLE_RESPONSE =
            "[\"Ruby programming language\",[\"Ruby (programming language)\","
                    + "\"Rust (programming language)\"],[\"\",\"\"],"
                    + "[\"https://en.wikipedia.org/wiki/Ruby_(programming_language)\","
                    + "\"https://en.wikipedia.org/wiki/Rust_(programming_language)\"]]";

    // opensearch 真实响应样本：单元素（精确命中——旧 bug 场景）
    private static final String SINGLE_TITLE_RESPONSE =
            "[\"Python programming language\",[\"Python (programming language)\"],[\"\"],"
                    + "[\"https://en.wikipedia.org/wiki/Python_(programming_language)\"]]";

    @Test
    void singleTitleExactMatchIsExtracted() {
        assertThat(extractFirstTitle(SINGLE_TITLE_RESPONSE))
                .as("精确命中（单元素数组）应提取出标题——旧 indexOf 门卫在此场景误报搜不到")
                .isEqualTo("Python (programming language)");
    }

    @Test
    void multiTitleMatchIsExtracted() {
        assertThat(extractFirstTitle(MULTI_TITLE_RESPONSE))
                .isEqualTo("Ruby (programming language)");
    }

    @Test
    void emptyResponseYieldsNull() {
        assertThat(extractFirstTitle("[]")).isNull();
        assertThat(extractFirstTitle("{}")).isNull();
    }

    /**
     * 直连 static 提取方法——只测提取，不发网络请求。
     *
     * @param body opensearch JSON 响应
     * @return 提取的首标题；无则 null
     */
    private static String extractFirstTitle(String body) {
        return WikipediaSearchTool.extractFirstTitle(body);
    }
}
