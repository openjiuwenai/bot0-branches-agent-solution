/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.tool.spi;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 本地工具的自描述元数据（FEAT-007 §L-01/05）。
 *
 * <p>标识与 wire 对齐：{@code toolId} 唯一，且<b>直接用作 ToolView 上报与 {@code _interrupt} 匹配的工具名</b>
 * （见 L2 Feat-Func-007：{@code clientTools[].name = toolId}，{@code _interrupt.toolName = toolId}）。
 *
 * <p>副作用分级（Observation/Action）用于治理：ACTION 通常需要审批，OBSERVATION 一般可直接执行。
 *
 * @since 2026-07-27
 */
public final class LocalToolDescriptor {

    private final String toolId;
    private final String displayName;
    private final String description;
    private final SideEffect sideEffect;
    private final String inputSchema;
    private final Set<String> requiredArgumentKeys;
    private final Duration timeout;
    private final boolean requiresApproval;

    /**
     * 工具副作用分级，用于治理决策。
     *
     * @since 2026-07-27
     */
    public enum SideEffect {
        /** 只读/无副作用，可直接执行。 */
        OBSERVATION,
        /** 有副作用（写操作等），执行前需经审批。 */
        ACTION
    }

    private LocalToolDescriptor(Builder b) {
        this.toolId = Objects.requireNonNull(b.toolId, "toolId");
        this.displayName = (b.displayName != null) ? b.displayName : b.toolId;
        this.description = (b.description != null) ? b.description : "";
        this.sideEffect = Objects.requireNonNull(b.sideEffect, "sideEffect");
        this.inputSchema = (b.inputSchema != null) ? b.inputSchema : "{\"type\":\"object\"}";
        this.requiredArgumentKeys =
                Collections.unmodifiableSet(new LinkedHashSet<>(b.requiredArgumentKeys));
        this.timeout = (b.timeout != null) ? b.timeout : Duration.ofSeconds(30);
        // ACTION 默认需要审批；OBSERVATION 默认不需要。可被显式覆盖。
        this.requiresApproval = (b.requiresApproval != null)
                ? b.requiresApproval
                : (b.sideEffect == SideEffect.ACTION);
    }

    /**
     * 工具唯一标识，同时是 wire 上的工具名。
     *
     * @return 工具标识
     */
    public String toolId() {
        return toolId;
    }

    /**
     * wire 上使用的工具名，等于 toolId。
     *
     * @return wire 上使用的工具名
     */
    public String wireName() {
        return toolId;
    }

    /**
     * 工具展示名（默认等于 toolId）。
     *
     * @return 展示名
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 工具描述文本。
     *
     * @return 描述
     */
    public String description() {
        return description;
    }

    /**
     * 副作用分级。
     *
     * @return 副作用分级
     */
    public SideEffect sideEffect() {
        return sideEffect;
    }

    /**
     * JSON Schema 文本，用于生成 ToolView 的 inputSchema。
     *
     * @return JSON Schema 文本，用于生成 ToolView 的 inputSchema。
     */
    public String inputSchema() {
        return inputSchema;
    }

    /**
     * 供 SDK 无第三方依赖地做最小参数校验（键存在性）。
     *
     * @return 供 SDK 无第三方依赖地做最小参数校验（键存在性）。
     */
    public Set<String> requiredArgumentKeys() {
        return requiredArgumentKeys;
    }

    /**
     * 工具超时时间（默认 30 秒）。
     *
     * @return 超时时间
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * 是否需要审批（ACTION 默认需要，OBSERVATION 默认不需要）。
     *
     * @return 需要审批返回 true
     */
    public boolean requiresApproval() {
        return requiresApproval;
    }

    /**
     * 创建构造器。
     *
     * @param toolId 工具唯一标识
     * @return 构造器实例
     */
    public static Builder builder(String toolId) {
        return new Builder(toolId);
    }

    public static final class Builder {
        private final String toolId;
        private String displayName;
        private String description;
        private SideEffect sideEffect = SideEffect.OBSERVATION;
        private String inputSchema;
        private final Set<String> requiredArgumentKeys = new LinkedHashSet<>();
        private Duration timeout;
        private Boolean requiresApproval;

        private Builder(String toolId) {
            this.toolId = toolId;
        }

        /**
         * 设置展示名（默认等于 toolId）。
         *
         * @param v 展示名
         * @return 本构造器
         */
        public Builder displayName(String v) {
            this.displayName = v;
            return this;
        }

        /**
         * 设置描述文本。
         *
         * @param v 描述
         * @return 本构造器
         */
        public Builder description(String v) {
            this.description = v;
            return this;
        }

        /**
         * 设置副作用分级（默认 OBSERVATION）。
         *
         * @param v 副作用分级
         * @return 本构造器
         */
        public Builder sideEffect(SideEffect v) {
            this.sideEffect = v;
            return this;
        }

        /**
         * 设置 inputSchema（默认 {@code {"type":"object"}}）。
         *
         * @param v JSON Schema 文本
         * @return 本构造器
         */
        public Builder inputSchema(String v) {
            this.inputSchema = v;
            return this;
        }

        /**
         * 追加必填参数键。
         *
         * @param keys 必填参数键
         * @return 本构造器
         */
        public Builder requiredArguments(String... keys) {
            Collections.addAll(this.requiredArgumentKeys, keys);
            return this;
        }

        /**
         * 设置超时时间（默认 30 秒）。
         *
         * @param v 超时时间
         * @return 本构造器
         */
        public Builder timeout(Duration v) {
            this.timeout = v;
            return this;
        }

        /**
         * 显式设置是否需要审批（默认按 sideEffect 推导）。
         *
         * @param v 是否需要审批
         * @return 本构造器
         */
        public Builder requiresApproval(boolean v) {
            this.requiresApproval = v;
            return this;
        }

        /**
         * 构建描述符实例。
         *
         * @return 描述符实例
         */
        public LocalToolDescriptor build() {
            return new LocalToolDescriptor(this);
        }
    }
}
