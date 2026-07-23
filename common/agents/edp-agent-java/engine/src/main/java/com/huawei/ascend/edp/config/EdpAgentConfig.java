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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * edp-agent.yaml 标准配置模型。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>承载 EDPAgent 标准 YAML 配置的反序列化结果。</li>
 *     <li>向 {@code EdpaRuntimeHandler} 提供模型、Prompt 和框架选项。</li>
 *     <li>作为 DeepAgentConfig 构造过程的输入模型。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>各字段 getter/setter：供 Jackson 反序列化和业务代码读取。</li>
 *     <li>{@link Framework}：框架级配置。</li>
 *     <li>{@link Options}：框架运行选项。</li>
 *     <li>{@link Model}：模型后端配置。</li>
 *     <li>{@link Prompt}：系统提示词配置。</li>
 * </ul>
 *
 * @since 2024-01-01
  *
 */

public class EdpAgentConfig {
    /**
     * Agent 名称。
     */
    private String name;

    /**
     * Agent 描述。
     */
    private String description;

    /**
     * 框架配置节点。
     */
    private Framework framework;

    /**
     * 模型配置节点。
     */
    private Model model;

    /**
     * Versatile 服务配置节点。
     */
    private Versatile versatile;

    /**
     * Prompt 配置节点。
     */
    private Prompt prompt;

    /**
     * Skill 配置节点。
     */
    private Skills skills;

    /**
     * Gets the name.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets the description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the description.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Gets the framework.
     */
    public Framework getFramework() {
        return framework;
    }

    /**
     * Sets the framework.
     */
    public void setFramework(Framework framework) {
        this.framework = framework;
    }

    /**
     * Gets the model.
     */
    public Model getModel() {
        return model;
    }

    /**
     * Sets the model.
     */
    public void setModel(Model model) {
        this.model = model;
    }

    /**
     * Gets the versatile.
     */
    public Versatile getVersatile() {
        return versatile;
    }

    /**
     * Sets the versatile.
     */
    public void setVersatile(Versatile versatile) {
        this.versatile = versatile;
    }

    /**
     * Gets the prompt.
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * Sets the prompt.
     */
    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    /**
     * Gets the skills.
     */
    public Skills getSkills() {
        return skills;
    }

    /**
     * Sets the skills.
     */
    public void setSkills(Skills skills) {
        this.skills = skills;
    }

    /**
     * 框架配置节点。
      *
     */

    public static class Framework {
        /**
         * 框架运行选项。
         */
        private Options options;

        /**
         * Gets the options.
         */
        public Options getOptions() {
            return options;
        }

        /**
         * Sets the options.
         */
        public void setOptions(Options options) {
            this.options = options;
        }
    }

    /**
     * 框架运行选项。
      *
     */

    public static class Options {
        /**
         * ReAct 最大迭代次数。
         */
        private int maxIterations;

        /**
         * 是否启用 task loop。
         */
        private boolean enableTaskLoop;

        /**
         * Gets the max iterations.
         */
        public int getMaxIterations() {
            return maxIterations;
        }

        /**
         * Sets the max iterations.
         */
        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        /**
         * Checks whether enable task loop.
         */
        public boolean isEnableTaskLoop() {
            return enableTaskLoop;
        }

        /**
         * Sets the enable task loop.
         */
        public void setEnableTaskLoop(boolean enableTaskLoop) {
            this.enableTaskLoop = enableTaskLoop;
        }
    }

    /**
     * 模型后端配置。
      *
     */

    public static class Model {
        /**
         * 模型 provider，例如 openai-compatible。
         */
        private String provider;

        /**
         * 模型名称，例如 deepseek-v4-pro。
         */
        private String name;

        /**
         * 模型服务 baseUrl。
         */
        private String baseUrl;

        /**
         * 模型服务 API Key。
         */
        private String apiKey;

        /**
         * Gets the provider.
         */
        public String getProvider() {
            return provider;
        }

        /**
         * Sets the provider.
         */
        public void setProvider(String provider) {
            this.provider = provider;
        }

        /**
         * Gets the name.
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the name.
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * Gets the base url.
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * Sets the base url.
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * Gets the api key.
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Sets the api key.
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /**
     * Versatile 服务配置。
      *
     */

    public static class Versatile {
        /**
         * Versatile REST 直连地址，含 {conversation_id} 等路径占位符。
         */
        private String url;

        /**
         * adapter-versatile-agent-java 的 A2A SSE 入口；配置后优先于 url 直连。
         */
        @JsonProperty("adapter_a2a_url")
        private String adapterA2aUrl;
        private String timeout = "30s";
        @JsonProperty("url_variables")
        private Map<String, String> urlVariables = new LinkedHashMap<>();
        @JsonProperty("query_params")
        private Map<String, String> queryParams = new LinkedHashMap<>();
        private Map<String, String> headers = new LinkedHashMap<>();

        /**
         * Gets the url.
         */
        public String getUrl() {
            return url;
        }

        /**
         * Sets the url.
         */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * Gets the adapter a2a url.
         */
        public String getAdapterA2aUrl() {
            return adapterA2aUrl;
        }

        /**
         * Sets the adapter a2a url.
         */
        public void setAdapterA2aUrl(String adapterA2aUrl) {
            this.adapterA2aUrl = adapterA2aUrl;
        }

        /**
         * Gets the timeout.
         */
        public String getTimeout() {
            return timeout;
        }

        /**
         * Sets the timeout.
         */
        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        /**
         * Gets the url variables.
         */
        public Map<String, String> getUrlVariables() {
            return urlVariables;
        }

        /**
         * Sets the url variables.
         */
        public void setUrlVariables(Map<String, String> urlVariables) {
            this.urlVariables = urlVariables != null ? urlVariables : new LinkedHashMap<>();
        }

        /**
         * Gets the query params.
         */
        public Map<String, String> getQueryParams() {
            return queryParams;
        }

        /**
         * Sets the query params.
         */
        public void setQueryParams(Map<String, String> queryParams) {
            this.queryParams = queryParams != null ? queryParams : new LinkedHashMap<>();
        }

        /**
         * Gets the headers.
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * Sets the headers.
         */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? headers : new LinkedHashMap<>();
        }
    }

    /**
     * Skill 配置。
      *
     */

    public static class Skills {
        private List<String> directories = new ArrayList<>();
        private String mode = "all";

        /**
         * Gets the directories.
         */
        public List<String> getDirectories() {
            return directories;
        }

        /**
         * Sets the directories.
         */
        public void setDirectories(List<String> directories) {
            this.directories = directories != null ? directories : new ArrayList<>();
        }

        /**
         * Gets the mode.
         */
        public String getMode() {
            return mode;
        }

        /**
         * Sets the mode.
         */
        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    /**
     * Prompt 配置。
      *
     */

    public static class Prompt {
        /**
         * 系统提示词。
         */
        private String system;

        /**
         * Gets the system.
         */
        public String getSystem() {
            return system;
        }

        /**
         * Sets the system.
         */
        public void setSystem(String system) {
            this.system = system;
        }
    }

    /**
     * 环境变量覆盖配置。
     *
     * 用于在 Spring Bean 创建阶段将环境变量中的密钥类配置注入到 agentConfig 中，
     * 因为 edp-agent.yaml 通过 Jackson 直读加载，不支持 Spring Boot ${...} 占位符替换。
      *
     */

    public static class EnvOverrides {
        /**
         * API Key，从 EDP_AGENT_MODEL_API_KEY 环境变量注入。
         */
        private String apiKey;

        /**
         * 模型 provider，从 EDP_AGENT_MODEL_PROVIDER 环境变量覆盖。
         */
        private String modelProvider;

        /**
         * 模型 name，从 EDP_AGENT_MODEL_NAME 环境变量覆盖。
         */
        private String modelName;

        /**
         * 模型 baseUrl，从 EDP_AGENT_MODEL_BASE_URL 环境变量覆盖。
         */
        private String modelBaseUrl;

        /**
         * Versatile URL，从 EDP_AGENT_VERSATILE_URL 环境变量覆盖。
         */
        private String versatileUrl;

        /**
         * Gets the api key.
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * Sets the api key.
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * Gets the model provider.
         */
        public String getModelProvider() {
            return modelProvider;
        }

        /**
         * Sets the model provider.
         */
        public void setModelProvider(String modelProvider) {
            this.modelProvider = modelProvider;
        }

        /**
         * Gets the model name.
         */
        public String getModelName() {
            return modelName;
        }

        /**
         * Sets the model name.
         */
        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        /**
         * Gets the model base url.
         */
        public String getModelBaseUrl() {
            return modelBaseUrl;
        }

        /**
         * Sets the model base url.
         */
        public void setModelBaseUrl(String modelBaseUrl) {
            this.modelBaseUrl = modelBaseUrl;
        }

        /**
         * Gets the versatile url.
         */
        public String getVersatileUrl() {
            return versatileUrl;
        }

        /**
         * Sets the versatile url.
         */
        public void setVersatileUrl(String versatileUrl) {
            this.versatileUrl = versatileUrl;
        }
    }
}
