/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 拒绝消息资源加载器（测试侧单一真源入口）。
 *
 * <p>4-lens 审查（Lens C）发现：测试硬编码副本与资源文件已漂移且全套静默绿。
 * 本类让所有测试从 {@code /prompts/veto-rejection.txt} 读真源——资源改动
 * 立即 RED，结构上消灭双源漂移。
 *
 * @since 2026-08
 */
final class RejectionResource {

    private RejectionResource() {
    }

    /**
     * 加载拒绝消息资源。
     *
     * @return 资源全文（trim）
     * @throws IllegalStateException 资源缺失（承重——不许静默回退硬编码）
     */
    static String load() {
        try (InputStream in = VetoRail.class.getResourceAsStream(
                "/prompts/veto-rejection.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "veto-rejection.txt missing from classpath — 真源不可缺");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("failed to load veto-rejection.txt", e);
        }
    }
}
