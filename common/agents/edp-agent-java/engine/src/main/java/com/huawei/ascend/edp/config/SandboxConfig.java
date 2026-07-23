/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱配置属性类。
 *
 * <p>映射 application.yml 中 {@code edpa.agent.sandbox.*} 配置段，
 * 控制沙箱模式的开关、超时、降级策略等行为。</p>
 *
 * @since 2024-01-01
  *
 */

@ConfigurationProperties(prefix = "edpa.agent.sandbox")
public class SandboxConfig {
    /**
     * 是否启用沙箱模式。
     */
    private boolean enabled = false;

    /**
     * jiuwenswarm 服务地址。
     */
    private String serviceUrl = "";

    /**
     * 沙箱 ID 前缀。
     */
    private String sandboxIdPrefix = "edp";

    /**
     * 创建沙箱超时（秒）。
     */
    private int createTimeoutSeconds = 30;

    /**
     * 执行命令超时（秒），对齐 SCRIPT_TIMEOUT。
     */
    private int execTimeoutSeconds = 60;

    /**
     * 技能包部署路径（沙箱内）。
     */
    private String skillDeployPath = "/app/skills";

    /**
     * 启动时自动创建沙箱。
     */
    private boolean autoCreateOnStartup = true;

    /**
     * 沙箱停止策略（delete/pause）。
     */
    private String onStop = "delete";

    /**
     * 沙箱不可用时自动降级到本地执行。
     */
    private boolean fallbackOnFailure = true;

    /**
     * 不走沙箱的命令列表（逗号分隔）。
     */
    private String excludedCommands = "";

    /**
     * 容器隔离级别（SESSION/SYSTEM/CUSTOM）。
     */
    private String containerScope = "SESSION";

    /**
     * Checks whether enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the enabled.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Gets the service url.
     */
    public String getServiceUrl() {
        return serviceUrl;
    }

    /**
     * Sets the service url.
     */
    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    /**
     * Gets the sandbox id prefix.
     */
    public String getSandboxIdPrefix() {
        return sandboxIdPrefix;
    }

    /**
     * Sets the sandbox id prefix.
     */
    public void setSandboxIdPrefix(String sandboxIdPrefix) {
        this.sandboxIdPrefix = sandboxIdPrefix;
    }

    /**
     * Gets the create timeout seconds.
     */
    public int getCreateTimeoutSeconds() {
        return createTimeoutSeconds;
    }

    /**
     * Sets the create timeout seconds.
     */
    public void setCreateTimeoutSeconds(int createTimeoutSeconds) {
        this.createTimeoutSeconds = createTimeoutSeconds;
    }

    /**
     * Gets the exec timeout seconds.
     */
    public int getExecTimeoutSeconds() {
        return execTimeoutSeconds;
    }

    /**
     * Sets the exec timeout seconds.
     */
    public void setExecTimeoutSeconds(int execTimeoutSeconds) {
        this.execTimeoutSeconds = execTimeoutSeconds;
    }

    /**
     * Gets the skill deploy path.
     */
    public String getSkillDeployPath() {
        return skillDeployPath;
    }

    /**
     * Sets the skill deploy path.
     */
    public void setSkillDeployPath(String skillDeployPath) {
        this.skillDeployPath = skillDeployPath;
    }

    /**
     * Checks whether auto create on startup.
     */
    public boolean isAutoCreateOnStartup() {
        return autoCreateOnStartup;
    }

    /**
     * Sets the auto create on startup.
     */
    public void setAutoCreateOnStartup(boolean autoCreateOnStartup) {
        this.autoCreateOnStartup = autoCreateOnStartup;
    }

    /**
     * Gets the on stop.
     */
    public String getOnStop() {
        return onStop;
    }

    /**
     * Sets the on stop.
     */
    public void setOnStop(String onStop) {
        this.onStop = onStop;
    }

    /**
     * Checks whether fallback on failure.
     */
    public boolean isFallbackOnFailure() {
        return fallbackOnFailure;
    }

    /**
     * Sets the fallback on failure.
     */
    public void setFallbackOnFailure(boolean fallbackOnFailure) {
        this.fallbackOnFailure = fallbackOnFailure;
    }

    /**
     * Gets the excluded commands.
     */
    public String getExcludedCommands() {
        return excludedCommands;
    }

    /**
     * Sets the excluded commands.
     */
    public void setExcludedCommands(String excludedCommands) {
        this.excludedCommands = excludedCommands;
    }

    /**
     * Gets the container scope.
     */
    public String getContainerScope() {
        return containerScope;
    }

    /**
     * Sets the container scope.
     */
    public void setContainerScope(String containerScope) {
        this.containerScope = containerScope;
    }
}
