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
     * 获取 Agent 名称。
     *
     * @return Agent 名称字符串
     */
    public String getName() {
        return name;
    }

    /**
     * 设置 Agent 名称。
     *
     * @param name Agent 名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取 Agent 描述信息。
     *
     * @return Agent 描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置 Agent 描述信息。
     *
     * @param description Agent 描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取框架配置节点。
     *
     * @return 框架配置对象
     */
    public Framework getFramework() {
        return framework;
    }

    /**
     * 设置框架配置节点。
     *
     * @param framework 框架配置对象
     */
    public void setFramework(Framework framework) {
        this.framework = framework;
    }

    /**
     * 获取模型配置节点。
     *
     * @return 模型配置对象
     */
    public Model getModel() {
        return model;
    }

    /**
     * 设置模型配置节点。
     *
     * @param model 模型配置对象
     */
    public void setModel(Model model) {
        this.model = model;
    }

    /**
     * 获取 Versatile 服务配置节点。
     *
     * @return Versatile 配置对象
     */
    public Versatile getVersatile() {
        return versatile;
    }

    /**
     * 设置 Versatile 服务配置节点。
     *
     * @param versatile Versatile 配置对象
     */
    public void setVersatile(Versatile versatile) {
        this.versatile = versatile;
    }

    /**
     * 获取 Prompt 配置节点。
     *
     * @return Prompt 配置对象
     */
    public Prompt getPrompt() {
        return prompt;
    }

    /**
     * 设置 Prompt 配置节点。
     *
     * @param prompt Prompt 配置对象
     */
    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    /**
     * 获取 Skill 配置节点。
     *
     * @return Skill 配置对象
     */
    public Skills getSkills() {
        return skills;
    }

    /**
     * 设置 Skill 配置节点。
     *
     * @param skills Skill 配置对象
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
         * 获取框架运行选项。
         *
         * @return 框架运行选项对象
         */
        public Options getOptions() {
            return options;
        }

        /**
         * 设置框架运行选项。
         *
         * @param options 框架运行选项对象
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
         * 获取 ReAct 最大迭代次数。
         *
         * @return 最大迭代次数
         */
        public int getMaxIterations() {
            return maxIterations;
        }

        /**
         * 设置 ReAct 最大迭代次数。
         *
         * @param maxIterations 最大迭代次数
         */
        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        /**
         * 判断是否启用 task loop。
         *
         * @return 启用返回 true，否则返回 false
         */
        public boolean isEnableTaskLoop() {
            return enableTaskLoop;
        }

        /**
         * 设置是否启用 task loop。
         *
         * @param enableTaskLoop 是否启用 task loop
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
         * 获取模型 provider 类型。
         *
         * @return provider 类型字符串
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 设置模型 provider 类型。
         *
         * @param provider provider 类型，如 openai-compatible
         */
        public void setProvider(String provider) {
            this.provider = provider;
        }

        /**
         * 获取模型名称。
         *
         * @return 模型名称
         */
        public String getName() {
            return name;
        }

        /**
         * 设置模型名称。
         *
         * @param name 模型名称，如 deepseek-v4-pro
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * 获取模型服务 baseUrl。
         *
         * @return baseUrl 地址
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置模型服务 baseUrl。
         *
         * @param baseUrl 模型服务地址
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 获取模型 API Key。
         *
         * @return API Key 字符串
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置模型 API Key。
         *
         * @param apiKey API Key
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
         * 获取 Versatile REST 直连地址。
         *
         * @return REST 地址，含路径占位符
         */
        public String getUrl() {
            return url;
        }

        /**
         * 设置 Versatile REST 直连地址。
         *
         * @param url REST 地址，含 {conversation_id} 等占位符
         */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * 获取 adapter A2A SSE 入口地址。
         *
         * @return A2A 入口地址
         */
        public String getAdapterA2aUrl() {
            return adapterA2aUrl;
        }

        /**
         * 设置 adapter A2A SSE 入口地址。
         *
         * @param adapterA2aUrl A2A 入口地址，配置后优先于 url 直连
         */
        public void setAdapterA2aUrl(String adapterA2aUrl) {
            this.adapterA2aUrl = adapterA2aUrl;
        }

        /**
         * 获取超时时间配置。
         *
         * @return 超时时间字符串，如 "30s"
         */
        public String getTimeout() {
            return timeout;
        }

        /**
         * 设置超时时间。
         *
         * @param timeout 超时时间，如 "30s"
         */
        public void setTimeout(String timeout) {
            this.timeout = timeout;
        }

        /**
         * 获取 URL 路径变量映射。
         *
         * @return 路径变量键值对
         */
        public Map<String, String> getUrlVariables() {
            return urlVariables;
        }

        /**
         * 设置 URL 路径变量映射。
         *
         * @param urlVariables 路径变量键值对
         */
        public void setUrlVariables(Map<String, String> urlVariables) {
            this.urlVariables = urlVariables != null ? urlVariables : new LinkedHashMap<>();
        }

        /**
         * 获取查询参数映射。
         *
         * @return 查询参数键值对
         */
        public Map<String, String> getQueryParams() {
            return queryParams;
        }

        /**
         * 设置查询参数映射。
         *
         * @param queryParams 查询参数键值对
         */
        public void setQueryParams(Map<String, String> queryParams) {
            this.queryParams = queryParams != null ? queryParams : new LinkedHashMap<>();
        }

        /**
         * 获取自定义请求头映射。
         *
         * @return 请求头键值对
         */
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * 设置自定义请求头映射。
         *
         * @param headers 请求头键值对
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
         * 获取 Skill 目录列表。
         *
         * @return Skill 目录路径列表
         */
        public List<String> getDirectories() {
            return directories;
        }

        /**
         * 设置 Skill 目录列表。
         *
         * @param directories Skill 目录路径列表
         */
        public void setDirectories(List<String> directories) {
            this.directories = directories != null ? directories : new ArrayList<>();
        }

        /**
         * 获取 Skill 加载模式。
         *
         * @return 加载模式，如 "all"
         */
        public String getMode() {
            return mode;
        }

        /**
         * 设置 Skill 加载模式。
         *
         * @param mode 加载模式，如 "all"
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
         * 获取系统提示词内容。
         *
         * @return 系统提示词字符串
         */
        public String getSystem() {
            return system;
        }

        /**
         * 设置系统提示词内容。
         *
         * @param system 系统提示词
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
         * 获取模型 API Key 环境覆盖值。
         *
         * @return API Key
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置模型 API Key 环境覆盖值。
         *
         * @param apiKey API Key
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 获取模型 provider 环境覆盖值。
         *
         * @return provider 类型
         */
        public String getModelProvider() {
            return modelProvider;
        }

        /**
         * 设置模型 provider 环境覆盖值。
         *
         * @param modelProvider provider 类型
         */
        public void setModelProvider(String modelProvider) {
            this.modelProvider = modelProvider;
        }

        /**
         * 获取模型名称环境覆盖值。
         *
         * @return 模型名称
         */
        public String getModelName() {
            return modelName;
        }

        /**
         * 设置模型名称环境覆盖值。
         *
         * @param modelName 模型名称
         */
        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        /**
         * 获取模型 baseUrl 环境覆盖值。
         *
         * @return 模型服务地址
         */
        public String getModelBaseUrl() {
            return modelBaseUrl;
        }

        /**
         * 设置模型 baseUrl 环境覆盖值。
         *
         * @param modelBaseUrl 模型服务地址
         */
        public void setModelBaseUrl(String modelBaseUrl) {
            this.modelBaseUrl = modelBaseUrl;
        }

        /**
         * 获取 Versatile URL 环境覆盖值。
         *
         * @return Versatile 服务地址
         */
        public String getVersatileUrl() {
            return versatileUrl;
        }

        /**
         * 设置 Versatile URL 环境覆盖值。
         *
         * @param versatileUrl Versatile 服务地址
         */
        public void setVersatileUrl(String versatileUrl) {
            this.versatileUrl = versatileUrl;
        }
    }
}
