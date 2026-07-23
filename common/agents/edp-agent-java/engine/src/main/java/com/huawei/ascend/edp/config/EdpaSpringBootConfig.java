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

import com.huawei.ascend.edp.config.SandboxConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EDPAgent Spring Boot 配置模型。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>承载 application.yml 中 {@code edpa.agent.*} 下的全部配置，替代 edp-agent.yaml 直读和 EdpAgentProperties。</li>
 *     <li>Phase 2 合并了原 EdpAgentProperties 的 scenarioHome 字段到此类（yamlPath/configPath 已于 0707 废弃移除）。</li>
 *     <li>Spring Boot 原生支持 {@code ${ENV_VAR:default}} 占位符替换，不再需要手写环境变量覆盖逻辑。</li>
 *     <li>对外提供 ModelConfig / VersatileConfig / McpSseConfig，供 EdpaExtHandler 和 EdpaAgentEnhancer 使用。</li>
 * </ul>
 *
 * <p>配置映射：</p>
 * <pre>
 * application.yml                  → EdpaSpringBootConfig
 * ─────────────────────────────────────────────────────
 * edpa.agent.scenario-home         → scenarioHome    (Phase 2 新增，原 EdpAgentProperties)
 * edpa.agent.model.provider        → model.provider
 * edpa.agent.model.name            → model.name
 * edpa.agent.model.base-url        → model.baseUrl
 * edpa.agent.model.api-key         → model.apiKey
 * edpa.agent.versatile.url         → versatile.url
 * edpa.agent.versatile.adapter-a2a-url → versatile.adapterA2aUrl
 * edpa.agent.versatile.timeout     → versatile.timeout
 * edpa.agent.versatile.url-variables   → versatile.urlVariables
 * edpa.agent.versatile.query-params    -> versatile.queryParams
 * edpa.agent.versatile.headers     -> versatile.headers
 * </pre>
 *
 * @since 2024-01-01
 */

@ConfigurationProperties(prefix = "edpa.agent")
public class EdpaSpringBootConfig {
    /** 场景配置路径。 */
    private String scenarioHome = "../scenarios/wealth-demo";

    /** 模型后端配置。 */
    private ModelConfig model;

    /** Versatile 服务配置。 */
    private VersatileConfig versatile;

    /** MCP SSE 连接配置。 */
    private McpSseConfig mcpsse;

    /** 沙箱配置。 */
    private SandboxConfig sandbox;

    /** Gets the scenario home. */
    public String getScenarioHome() {
        return scenarioHome;
    }

    /** Sets the scenario home. */
    public void setScenarioHome(String scenarioHome) {
        this.scenarioHome = scenarioHome;
    }

    /** Gets the model. */
    public ModelConfig getModel() {
        return model;
    }

    /** Sets the model. */
    public void setModel(ModelConfig model) {
        this.model = model;
    }

    /** Gets the versatile. */
    public VersatileConfig getVersatile() {
        return versatile;
    }

    /** Sets the versatile. */
    public void setVersatile(VersatileConfig versatile) {
        this.versatile = versatile;
    }

    /** Gets the mcpsse. */
    public McpSseConfig getMcpsse() {
        return mcpsse;
    }

    /** Sets the mcpsse. */
    public void setMcpsse(McpSseConfig mcpsse) {
        this.mcpsse = mcpsse;
    }

    /** Gets the sandbox. */
    public SandboxConfig getSandbox() {
        return sandbox;
    }

    /** Sets the sandbox. */
    public void setSandbox(SandboxConfig sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 模型后端配置。
     */

    public static class ModelConfig {
        /** 模型 provider，例如 openai-compatible。 */
        private String provider;

        /** 模型名称，例如 deepseek-v4-pro。 */
        private String name;

        /** 模型服务 baseUrl。 */
        private String baseUrl;

        /** 模型服务 API Key（支持环境变量 ${EDP_AGENT_MODEL_API_KEY:} 注入）。 */
        private String apiKey;

        /** Gets the provider. */
        public String getProvider() {
            return provider;
        }

        /** Sets the provider. */
        public void setProvider(String provider) {
            this.provider = provider;
        }

        /** Gets the name. */
        public String getName() {
            return name;
        }

        /** Sets the name. */
        public void setName(String name) {
            this.name = name;
        }

        /** Gets the base url. */
        public String getBaseUrl() {
            return baseUrl;
        }

        /** Sets the base url. */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** Gets the api key. */
        public String getApiKey() {
            return apiKey;
        }

        /** Sets the api key. */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * Versatile 服务配置。
     */

    public static class VersatileConfig {
        /** Versatile REST 直连地址，含 {conversation_id} 等路径占位符。 */
        private String url;

        /** adapter-versatile-agent-java 的 A2A SSE 入口。 */
        private String adapterA2aUrl;

        /** 调用超时。 */
        private String timeout = "30s";

        /** URL 路径变量占位。 */
        private Map<String, String> urlVariables = new LinkedHashMap<>();

        /** 查询参数。 */
        private Map<String, String> queryParams = new LinkedHashMap<>();

        /** 请求头。 */
        private Map<String, String> headers = new LinkedHashMap<>();

        /** Gets the url. */
        public String getUrl() {
            return url;
        }

        /** Sets the url. */
        public void setUrl(String url) {
            this.url = url;
        }

        /** Gets the adapter a2a url. */
        public String getAdapterA2aUrl() {
            return adapterA2aUrl;
        }

        /** Sets the adapter a2a url. */
        public void setAdapterA2aUrl(String adapterA2aUrl) {
            this.adapterA2aUrl = adapterA2aUrl;
        }

        /** Gets the timeout. */
        public String getTimeout() {
            return timeout;
        }

        /** Sets the timeout. */
        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        /** Gets the url variables. */
        public Map<String, String> getUrlVariables() {
            return urlVariables;
        }

        /** Sets the url variables. */
        public void setUrlVariables(Map<String, String> urlVariables) {
            this.urlVariables = urlVariables != null ? urlVariables : new LinkedHashMap<>();
        }

        /** Gets the query params. */
        public Map<String, String> getQueryParams() {
            return queryParams;
        }

        /** Sets the query params. */
        public void setQueryParams(Map<String, String> queryParams) {
            this.queryParams = queryParams != null ? queryParams : new LinkedHashMap<>();
        }

        /** Gets the headers. */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /** Sets the headers. */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? headers : new LinkedHashMap<>();
        }
    }

    /**
     * MCP SSE 连接配置。
     */

    public static class McpSseConfig {
        /** MCP SSE 主 URL（灰度：wap_grayFlag 以 JD 开头时使用）。 */
        private String masterUrl;

        /** MCP SSE 备 URL（灰度：wap_grayFlag 非 JD 开头时使用）。 */
        private String standbyUrl;

        /** MCP SSE 鉴权 Token。 */
        private String accessToken;

        /** MCP SSE 应用名称。 */
        private String appName;

        /** Gets the master url. */
        public String getMasterUrl() {
            return masterUrl;
        }

        /** Sets the master url. */
        public void setMasterUrl(String masterUrl) {
            this.masterUrl = masterUrl;
        }

        /** Gets the standby url. */
        public String getStandbyUrl() {
            return standbyUrl;
        }

        /** Sets the standby url. */
        public void setStandbyUrl(String standbyUrl) {
            this.standbyUrl = standbyUrl;
        }

        /** Gets the access token. */
        public String getAccessToken() {
            return accessToken;
        }

        /** Sets the access token. */
        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        /** Gets the app name. */
        public String getAppName() {
            return appName;
        }

        /** Sets the app name. */
        public void setAppName(String appName) {
            this.appName = appName;
        }
    }
}
