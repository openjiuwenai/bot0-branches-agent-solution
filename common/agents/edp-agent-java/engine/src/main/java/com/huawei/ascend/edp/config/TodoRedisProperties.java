/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
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

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
/**
 * Redis TodoStore 配置属性。
 *
 * <p>对应配置前缀 {@code edpa.agent.redis}，承载部署模式、连接参数、TodoStore 与 Checkpointer TTL。
 * 设计参考：FEAT_EDPA Redis 存储设计方案 §2.2.1。</p>
 */
@ConfigurationProperties(prefix = "edpa.agent.redis")
/**
 * TodoRedisProperties class.
 *
 * @since 2024-01-01
 */
public class TodoRedisProperties {

    /** 部署模式：single | sentinel | cluster。 */
    private String mode = "single";

    /** 单机模式主机。 */
    private String host = "localhost";

    /** 单机模式端口。 */
    private int port = 6379;

    /** 认证密码（环境变量 EDPA_REDIS_PASSWORD 注入）。 */
    private String password;

    /** 数据库索引。 */
    private int database = 0;

    /** 连接建立超时（毫秒）。 */
    private int connectTimeoutMs = 5000;

    /** Socket 读写超时（毫秒）。 */
    private int socketTimeoutMs = 10000;

    /** 哨兵模式配置。 */
    private SentinelConfig sentinel = new SentinelConfig();

    /** 集群模式配置。 */
    private ClusterConfig cluster = new ClusterConfig();

    /** TodoStore 配置。 */
    private TodoConfig todo = new TodoConfig();

    /** Checkpointer TTL（分钟），UC-18。 */
    private int checkpointerTtlMinutes = 60;

    /** Gets the mode. */
    public String getMode() {
        return mode;
    }

    /** Sets the mode. */
    public void setMode(String mode) {
        this.mode = mode;
    }

    /** Gets the host. */
    public String getHost() {
        return host;
    }

    /** Sets the host. */
    public void setHost(String host) {
        this.host = host;
    }

    /** Gets the port. */
    public int getPort() {
        return port;
    }

    /** Sets the port. */
    public void setPort(int port) {
        this.port = port;
    }

    /** Gets the password. */
    public String getPassword() {
        return password;
    }

    /** Sets the password. */
    public void setPassword(String password) {
        this.password = password;
    }

    /** Gets the database. */
    public int getDatabase() {
        return database;
    }

    /** Sets the database. */
    public void setDatabase(int database) {
        this.database = database;
    }

    /** Gets the connect timeout ms. */
    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** Sets the connect timeout ms. */
    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** Gets the socket timeout ms. */
    public int getSocketTimeoutMs() {
        return socketTimeoutMs;
    }

    /** Sets the socket timeout ms. */
    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    /** Gets the sentinel. */
    public SentinelConfig getSentinel() {
        return sentinel;
    }

    /** Sets the sentinel. */
    public void setSentinel(SentinelConfig sentinel) {
        this.sentinel = sentinel;
    }

    /** Gets the cluster. */
    public ClusterConfig getCluster() {
        return cluster;
    }

    /** Sets the cluster. */
    public void setCluster(ClusterConfig cluster) {
        this.cluster = cluster;
    }

    /** Gets the todo. */
    public TodoConfig getTodo() {
        return todo;
    }

    /** Sets the todo. */
    public void setTodo(TodoConfig todo) {
        this.todo = todo;
    }

    /** Gets the checkpointer ttl minutes. */
    public int getCheckpointerTtlMinutes() {
        return checkpointerTtlMinutes;
    }

    /** Sets the checkpointer ttl minutes. */
    public void setCheckpointerTtlMinutes(int checkpointerTtlMinutes) {
        this.checkpointerTtlMinutes = checkpointerTtlMinutes;
    }

    /** TodoStore 配置：Key 前缀、TTL、读时续期。 */
    public static class TodoConfig {
        /** Redis Key 前缀，默认 {@code edpa}。 */
        private String keyPrefix = "edpa";

        /** TTL（秒），默认 3600（60min）。 */
        private long ttlSeconds = 3600;

        /** 读时是否续期，默认 true（UC-04）。 */
        private boolean refreshOnRead = true;

        /** Gets the key prefix. */
        public String getKeyPrefix() {
            return keyPrefix;
        }

        /** Sets the key prefix. */
        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        /** Gets the ttl seconds. */
        public long getTtlSeconds() {
            return ttlSeconds;
        }

        /** Sets the ttl seconds. */
        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        /** Checks whether refresh on read. */
        public boolean isRefreshOnRead() {
            return refreshOnRead;
        }

        /** Sets the refresh on read. */
        public void setRefreshOnRead(boolean refreshOnRead) {
            this.refreshOnRead = refreshOnRead;
        }
    }

    /** 哨兵模式配置。 */
    public static class SentinelConfig {
        /** 主节点名（如 mymaster）。 */
        private String master;

        /** 哨兵节点列表（host:port）。 */
        private List<String> nodes = new ArrayList<>();

        /** 哨兵认证密码。 */
        private String password;

        /** Gets the master. */
        public String getMaster() {
            return master;
        }

        /** Sets the master. */
        public void setMaster(String master) {
            this.master = master;
        }

        /** Gets the nodes. */
        public List<String> getNodes() {
            return nodes;
        }

        /** Sets the nodes. */
        public void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }

        /** Gets the password. */
        public String getPassword() {
            return password;
        }

        /** Sets the password. */
        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** 集群模式配置。 */
    public static class ClusterConfig {
        /** 集群节点列表（host:port）。 */
        private List<String> nodes = new ArrayList<>();

        /** 最大重定向次数。 */
        private int maxRedirects = 3;

        /** Gets the nodes. */
        public List<String> getNodes() {
            return nodes;
        }

        /** Sets the nodes. */
        public void setNodes(List<String> nodes) {
            this.nodes = nodes;
        }

        /** Gets the max redirects. */
        public int getMaxRedirects() {
            return maxRedirects;
        }

        /** Sets the max redirects. */
        public void setMaxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
        }
    }
}
