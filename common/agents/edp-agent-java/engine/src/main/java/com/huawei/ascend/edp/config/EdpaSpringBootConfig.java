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
 *     <li>承载 application.yml 中 {@code deep-agent.*} 下的全部配置，替代 edp-agent.yaml 直读和 EdpAgentProperties。</li>
 *     <li>Phase 2 合并了原 EdpAgentProperties 的 scenarioHome 字段到此类（yamlPath/configPath 已于 0707 废弃移除）。</li>
 *     <li>Spring Boot 原生支持 {@code ${ENV_VAR:default}} 占位符替换，不再需要手写环境变量覆盖逻辑。</li>
 *     <li>对外提供 ModelConfig / VersatileConfig / McpSseConfig，供 EdpaExtHandler 和 EdpaAgentEnhancer 使用。</li>
 * </ul>
 *
 * <p>配置映射：</p>
 * <pre>
 * application.yml                  → EdpaSpringBootConfig
 * ─────────────────────────────────────────────────────
 * deep-agent.scenario-home         → scenarioHome    (Phase 2 新增，原 EdpAgentProperties)
 * deep-agent.model.provider        -> model.provider
 * deep-agent.model.name            -> model.name
 * deep-agent.model.base-url        -> model.baseUrl
 * deep-agent.model.api-key         -> model.apiKey
 * deep-agent.versatile.url         -> versatile.url
 * deep-agent.versatile.adapter-a2a-url -> versatile.adapterA2aUrl
 * deep-agent.versatile.timeout     -> versatile.timeout
 * deep-agent.versatile.url-variables   -> versatile.urlVariables
 * deep-agent.versatile.query-params    -> versatile.queryParams
 * deep-agent.versatile.headers     -> versatile.headers
 * </pre>
 *
 * @since 2024-01-01
 *
 */

@ConfigurationProperties(prefix = "deep-agent")
public class EdpaSpringBootConfig {
    /**
     * 场景配置路径。
     */
    private String scenarioHome = "../scenarios/wealth-demo";

    /**
     * Versatile 服务配置。
     */
    private VersatileConfig versatile;

    /**
     * MCP SSE 连接配置。
     */
    private McpSseConfig mcpsse;

    /**
     * 沙箱配置。
     */
    private SandboxConfig sandbox;

    /**
     * Gets the scenario home.
     *
     * @return the result
     */
    public String getScenarioHome() {
        return scenarioHome;
    }

    /**
     * Sets the scenario home.
     *
     * @param scenarioHome the scenarioHome value
     */
    public void setScenarioHome(String scenarioHome) {
        this.scenarioHome = scenarioHome;
    }

    /**
     * Gets the versatile.
     *
     * @return the result
     */
    public VersatileConfig getVersatile() {
        return versatile;
    }

    /**
     * Sets the versatile.
     *
     * @param versatile the versatile value
     */
    public void setVersatile(VersatileConfig versatile) {
        this.versatile = versatile;
    }

    /**
     * Gets the mcpsse.
     *
     * @return the result
     */
    public McpSseConfig getMcpsse() {
        return mcpsse;
    }

    /**
     * Sets the mcpsse.
     *
     * @param mcpsse the mcpsse value
     */
    public void setMcpsse(McpSseConfig mcpsse) {
        this.mcpsse = mcpsse;
    }

    /**
     * Gets the sandbox.
     *
     * @return the result
     */
    public SandboxConfig getSandbox() {
        return sandbox;
    }

    /**
     * Sets the sandbox.
     *
     * @param sandbox the sandbox value
     */
    public void setSandbox(SandboxConfig sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 模型参数配置（模型名称、思维链等）。
     *
     */

    public static class ModelConfig {
        /**
         * 模型名称，例如 deepseek-v4-pro。
         */
        private String model;

        /**
         * 思维链配置。
         */
        private ThinkingConfig thinking;

        /**
         * Gets the model.
         *
         * @return the result
         */
        public String getModel() {
            return model;
        }

        /**
         * Sets the model.
         *
         * @param model the model value
         */
        public void setModel(String model) {
            this.model = model;
        }

        /**
         * Gets the thinking.
         *
         * @return the result
         */
        public ThinkingConfig getThinking() {
            return thinking;
        }

        /**
         * Sets the thinking.
         *
         * @param thinking the thinking value
         */
        public void setThinking(ThinkingConfig thinking) {
            this.thinking = thinking;
        }
    }

    /**
     * 模型后端连接配置（服务端连接参数）。
     *
     */

    public static class BackendConfig {
        /**
         * 模型提供商名称（如 openai、anthropic、deepseek 等）。
         */
        private String clientProvider;

        /**
         * API 密钥。
         */
        private String apiKey;

        /**
         * API 基础地址。
         */
        private String apiBase;

        /**
         * 请求超时时间（秒），必须 > 0。
         */
        private double timeout = 120.0;

        /**
         * 最大重试次数。
         */
        private int maxRetries = 5;

        /**
         * 是否验证 SSL 证书。
         */
        private boolean verifySsl = false;

        /**
         * 自定义 SSL 证书路径（可选）。
         */
        private String sslCert;

        /**
         * 客户端 ID（可选，不填会自动生成 UUID）。
         */
        private String clientId;

        /**
         * Gets the client provider.
         *
         * @return the result
         */
        public String getClientProvider() {
            return clientProvider;
        }

        /**
         * Sets the client provider.
         *
         * @param clientProvider the clientProvider value
         */
        public void setClientProvider(String clientProvider) {
            this.clientProvider = clientProvider;
        }

        /**
         * Gets the api key.
         *
         * @return the result
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Sets the api key.
         *
         * @param apiKey the apiKey value
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Gets the api base.
         *
         * @return the result
         */
        public String getApiBase() {
            return apiBase;
        }

        /**
         * Sets the api base.
         *
         * @param apiBase the apiBase value
         */
        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        /**
         * Gets the timeout.
         *
         * @return the result
         */
        public double getTimeout() {
            return timeout;
        }

        /**
         * Sets the timeout.
         *
         * @param timeout the timeout value
         */
        public void setTimeout(double timeout) {
            this.timeout = timeout;
        }

        /**
         * Gets the max retries.
         *
         * @return the result
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Sets the max retries.
         *
         * @param maxRetries the maxRetries value
         */
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        /**
         * Is verify ssl.
         *
         * @return the result
         */
        public boolean isVerifySsl() {
            return verifySsl;
        }

        /**
         * Sets the verify ssl.
         *
         * @param verifySsl the verifySsl value
         */
        public void setVerifySsl(boolean verifySsl) {
            this.verifySsl = verifySsl;
        }

        /**
         * Gets the ssl cert.
         *
         * @return the result
         */
        public String getSslCert() {
            return sslCert;
        }

        /**
         * Sets the ssl cert.
         *
         * @param sslCert the sslCert value
         */
        public void setSslCert(String sslCert) {
            this.sslCert = sslCert;
        }

        /**
         * Gets the client id.
         *
         * @return the result
         */
        public String getClientId() {
            return clientId;
        }

        /**
         * Sets the client id.
         *
         * @param clientId the clientId value
         */
        public void setClientId(String clientId) {
            this.clientId = clientId;
        }
    }

    /**
     * 思维链配置。
     *
     */

    public static class ThinkingConfig {
        /**
         * 思维链类型（disabled / enabled）。
         */
        private String type;

        /**
         * Gets the type.
         *
         * @return the result
         */
        public String getType() {
            return type;
        }

        /**
         * Sets the type.
         *
         * @param type the type value
         */
        public void setType(String type) {
            this.type = type;
        }
    }

    /**
     * Versatile 服务配置。
     *
     */

    public static class VersatileConfig {
        /**
         * Versatile REST 直连地址，含 {conversation_id} 等路径占位符。
         */
        private String url;

        /**
         * adapter-versatile-agent-java 的 A2A SSE 入口。
         */
        private String adapterA2aUrl;

        /**
         * 调用超时。
         */
        private String timeout = "30s";

        /**
         * URL 路径变量占位。
         *
         * @return the result
         */
        private Map<String, String> urlVariables = new LinkedHashMap<>();

        /**
         * 查询参数。
         *
         * @return the result
         */
        private Map<String, String> queryParams = new LinkedHashMap<>();

        /**
         * 请求头。
         *
         * @return the result
         */
        private Map<String, String> headers = new LinkedHashMap<>();

        /**
         * 熔断器配置。
         */
        private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

        /**
         * Gets the url.
         *
         * @return the result
         */
        public String getUrl() {
            return url;
        }

        /**
         * Sets the url.
         *
         * @param url the url value
         */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * Gets the adapter a2a url.
         *
         * @return the result
         */
        public String getAdapterA2aUrl() {
            return adapterA2aUrl;
        }

        /**
         * Sets the adapter a2a url.
         *
         * @param adapterA2aUrl the adapterA2aUrl value
         */
        public void setAdapterA2aUrl(String adapterA2aUrl) {
            this.adapterA2aUrl = adapterA2aUrl;
        }

        /**
         * Gets the timeout.
         *
         * @return the result
         */
        public String getTimeout() {
            return timeout;
        }

        /**
         * Sets the timeout.
         *
         * @param timeout the timeout value
         */
        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        /**
         * Gets the url variables.
         *
         * @return the result
         */
        public Map<String, String> getUrlVariables() {
            return urlVariables;
        }

        /**
         * Sets the url variables.
         *
         * @param urlVariables the urlVariables value
         */
        public void setUrlVariables(Map<String, String> urlVariables) {
            this.urlVariables = urlVariables != null ? urlVariables : new LinkedHashMap<>();
        }

        /**
         * Gets the query params.
         *
         * @return the result
         */
        public Map<String, String> getQueryParams() {
            return queryParams;
        }

        /**
         * Sets the query params.
         *
         * @param queryParams the queryParams value
         */
        public void setQueryParams(Map<String, String> queryParams) {
            this.queryParams = queryParams != null ? queryParams : new LinkedHashMap<>();
        }

        /**
         * Gets the headers.
         *
         * @return the result
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * Sets the headers.
         *
         * @param headers the headers value
         */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? headers : new LinkedHashMap<>();
        }

        /**
         * Gets the circuit breaker config.
         *
         * @return the result
         */
        public CircuitBreakerConfig getCircuitBreaker() {
            return circuitBreaker;
        }

        /**
         * Sets the circuit breaker config.
         *
         * @param circuitBreaker the circuitBreaker value
         */
        public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) {
            this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreakerConfig();
        }

        /**
         * 熔断器参数配置。
         */
        public static class CircuitBreakerConfig {
            /**
             * 是否启用熔断器。
             */
            private boolean enabled = true;

            /**
             * 连续失败达到此阈值后熔断器打开。
             */
            private int failureThreshold = 5;

            /**
             * 熔断打开后经过此时间进入半开状态（毫秒）。
             */
            private long resetTimeoutMs = 30000L;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getFailureThreshold() {
                return failureThreshold;
            }

            public void setFailureThreshold(int failureThreshold) {
                this.failureThreshold = failureThreshold;
            }

            public long getResetTimeoutMs() {
                return resetTimeoutMs;
            }

            public void setResetTimeoutMs(long resetTimeoutMs) {
                this.resetTimeoutMs = resetTimeoutMs;
            }
        }
    }

    /**
     * MCP SSE 连接配置。
     *
     */

    public static class McpSseConfig {
        /**
         * MCP SSE 主 URL（灰度：wap_grayFlag 以 JD 开头时使用）。
         */
        private String masterUrl;

        /**
         * MCP SSE 备 URL（灰度：wap_grayFlag 非 JD 开头时使用）。
         */
        private String standbyUrl;

        /**
         * MCP SSE 鉴权 Token。
         */
        private String accessToken;

        /**
         * MCP SSE 应用名称。
         */
        private String appName;

        /**
         * Gets the master url.
         *
         * @return the result
         */
        public String getMasterUrl() {
            return masterUrl;
        }

        /**
         * Sets the master url.
         *
         * @param masterUrl the masterUrl value
         */
        public void setMasterUrl(String masterUrl) {
            this.masterUrl = masterUrl;
        }

        /**
         * Gets the standby url.
         *
         * @return the result
         */
        public String getStandbyUrl() {
            return standbyUrl;
        }

        /**
         * Sets the standby url.
         *
         * @param standbyUrl the standbyUrl value
         */
        public void setStandbyUrl(String standbyUrl) {
            this.standbyUrl = standbyUrl;
        }

        /**
         * Gets the access token.
         *
         * @return the result
         */
        public String getAccessToken() {
            return accessToken;
        }

        /**
         * Sets the access token.
         *
         * @param accessToken the accessToken value
         */
        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        /**
         * Gets the app name.
         *
         * @return the result
         */
        public String getAppName() {
            return appName;
        }

        /**
         * Sets the app name.
         *
         * @param appName the appName value
         */
        public void setAppName(String appName) {
            this.appName = appName;
        }
    }
}
