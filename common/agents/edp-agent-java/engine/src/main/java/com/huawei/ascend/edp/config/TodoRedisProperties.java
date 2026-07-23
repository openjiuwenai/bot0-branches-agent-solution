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

import java.util.ArrayList;
import java.util.List;

/**
 * Redis TodoStore 配置属性。
 *
 * <p>对应配置前缀 {@code edpa.agent.redis}，承载部署模式、连接参数、TodoStore 与 Checkpointer TTL。
 * 设计参考：FEAT_EDPA Redis 存储设计方案 §2.2.1。</p>
 *
 * @since 2024-01-01
 *
 */

@ConfigurationProperties(prefix = "edpa.agent.redis")
public class TodoRedisProperties {
    /**
     * 部署模式：single | sentinel | cluster。
     */
    private String mode = "single";

    /**
     * 单机模式主机。
     */
    private String host = "localhost";

    /**
     * 单机模式端口。
     */
    private int port = 6379;

    /**
     * 认证密码（环境变量 EDPA_REDIS_PASSWORD 注入）。
     */
    private String password;

    /**
     * 数据库索引。
     */
    private int database = 0;

    /**
     * 连接建立超时（毫秒）。
     */
    private int connectTimeoutMs = 5000;

    /**
     * Socket 读写超时（毫秒）。
     */
    private int socketTimeoutMs = 10000;

    /**
     * 哨兵模式配置。
     *
     * @return the result
     */
    private SentinelConfig sentinel = new SentinelConfig();

    /**
     * 集群模式配置。
     *
     * @return the result
     */
    private ClusterConfig cluster = new ClusterConfig();

    /**
     * TodoStore 配置。
     *
     * @return the result
     */
    private TodoConfig todo = new TodoConfig();

    /**
     * Checkpointer TTL（分钟），UC-18。
     */
    private int checkpointerTtlMinutes = 60;

    /**
     * Gets the mode.
     *
     * @return the result
     */
    public String getMode() {
        return mode;
    }

    /**
     * Sets the mode.
     *
     * @param mode the mode value
     */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * Gets the host.
     *
     * @return the result
     */
    public String getHost() {
        return host;
    }

    /**
     * Sets the host.
     *
     * @param host the host value
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Gets the port.
     *
     * @return the result
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the port.
     *
     * @param port the port value
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Gets the password.
     *
     * @return the result
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password.
     *
     * @param password the password value
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Gets the database.
     *
     * @return the result
     */
    public int getDatabase() {
        return database;
    }

    /**
     * Sets the database.
     *
     * @param database the database value
     */
    public void setDatabase(int database) {
        this.database = database;
    }

    /**
     * Gets the connect timeout ms.
     *
     * @return the result
     */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /**
     * Sets the connect timeout ms.
     *
     * @param connectTimeoutMs the connectTimeoutMs value
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * Gets the socket timeout ms.
     *
     * @return the result
     */
    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    /**
     * Sets the socket timeout ms.
     *
     * @param socketTimeoutMs the socketTimeoutMs value
     */
    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    /**
     * Gets the sentinel.
     *
     * @return the result
     */
    public SentinelConfig getSentinel() {
        return sentinel;
    }

    /**
     * Sets the sentinel.
     *
     * @param sentinel the sentinel value
     */
    public void setSentinel(SentinelConfig sentinel) {
        this.sentinel = sentinel;
    }

    /**
     * Gets the cluster.
     *
     * @return the result
     */
    public ClusterConfig getCluster() {
        return cluster;
    }

    /**
     * Sets the cluster.
     *
     * @param cluster the cluster value
     */
    public void setCluster(ClusterConfig cluster) {
        this.cluster = cluster;
    }

    /**
     * Gets the todo.
     *
     * @return the result
     */
    public TodoConfig getTodo() {
        return todo;
    }

    /**
     * Sets the todo.
     *
     * @param todo the todo value
     */
    public void setTodo(TodoConfig todo) {
        this.todo = todo;
    }

    /**
     * Gets the checkpointer ttl minutes.
     *
     * @return the result
     */
    public int getCheckpointerTtlMinutes() {
        return checkpointerTtlMinutes;
    }

    /**
     * Sets the checkpointer ttl minutes.
     *
     * @param checkpointerTtlMinutes the checkpointerTtlMinutes value
     */
    public void setCheckpointerTtlMinutes(int checkpointerTtlMinutes) {
        this.checkpointerTtlMinutes = checkpointerTtlMinutes;
    }

    /**
     * TodoStore 配置：Key 前缀、TTL、读时续期。
     */
    public static class TodoConfig {
        /**
         * Redis Key 前缀，默认 {@code edpa}。
         */
        private String keyPrefix = "edpa";

        /**
         * TTL（秒），默认 3600（60min）。
         */
        private long ttlSeconds = 3600L;

        /**
         * 读时是否续期，默认 true（UC-04）。
         */
        private boolean refreshOnRead = true;

        /**
         * Gets the key prefix.
         *
         * @return the result
         */
        public String getKeyPrefix() {
            return keyPrefix;
        }

        /**
         * Sets the key prefix.
         *
         * @param keyPrefix the keyPrefix value
         */
        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        /**
         * Gets the ttl seconds.
         *
         * @return the result
         */
        public long getTtlSeconds() {
            return ttlSeconds;
        }

        /**
         * Sets the ttl seconds.
         *
         * @param ttlSeconds the ttlSeconds value
         */
        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        /**
         * Checks whether refresh on read.
         *
         * @return the result
         */
        public boolean isRefreshOnRead() {
            return refreshOnRead;
        }

        /**
         * Sets the refresh on read.
         *
         * @param refreshOnRead the refreshOnRead value
         */
        public void setRefreshOnRead(boolean refreshOnRead) {
            this.refreshOnRead = refreshOnRead;
        }
    }

    /**
     * 哨兵模式配置。
     */
    public static class SentinelConfig {
        /**
         * 主节点名（如 mymaster）。
         */
        private String master;

        /**
         * 哨兵节点列表（host:port）。
         *
         * @return the result
         */
        private List<String> nodes = new ArrayList<>();

        /**
         * 哨兵认证密码。
         */
        private String password;

        /**
         * Gets the master.
         *
         * @return the result
         */
        public String getMaster() {
            return master;
        }

        /**
         * Sets the master.
         *
         * @param master the master value
         */
        public void setMaster(String master) {
            this.master = master;
        }

        /**
         * Gets the nodes.
         *
         * @return the result
         */
        public List<String> getNodes() {
            return nodes;
        }

        /**
         * Sets the nodes.
         *
         * @param nodes the nodes value
         */
        public void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }

        /**
         * Gets the password.
         *
         * @return the result
         */
        public String getPassword() {
            return password;
        }

        /**
         * Sets the password.
         *
         * @param password the password value
         */
        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * 集群模式配置。
     */
    public static class ClusterConfig {
        /**
         * 集群节点列表（host:port）。
         *
         * @return the result
         */
        private List<String> nodes = new ArrayList<>();

        /**
         * 最大重定向次数。
         */
        private int maxRedirects = 3;

        /**
         * Gets the nodes.
         *
         * @return the result
         */
        public List<String> getNodes() {
            return nodes;
        }

        /**
         * Sets the nodes.
         *
         * @param nodes the nodes value
         */
        public void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }

        /**
         * Gets the max redirects.
         *
         * @return the result
         */
        public int getMaxRedirects() {
            return maxRedirects;
        }

        /**
         * Sets the max redirects.
         *
         * @param maxRedirects the maxRedirects value
         */
        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }
    }
}
