# Redis 集成指南

## 1. 配置前缀与参数表

Redis 配置前缀为 `edpa.agent.redis`，对应类 `TodoRedisProperties`。

### 1.1 基础连接参数

| 参数 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `mode` | `EDPA_REDIS_MODE` | `single` | 部署模式：`single` / `sentinel` / `cluster` |
| `host` | `EDPA_REDIS_HOST` | `localhost` | Redis 主机地址（single 模式） |
| `port` | `EDPA_REDIS_PORT` | `6379` | Redis 端口（single 模式） |
| `password` | `EDPA_REDIS_PASSWORD` | （空） | 认证密码 |
| `database` | `EDPA_REDIS_DB` | `0` | 数据库索引 |
| `connect-timeout-ms` | `EDPA_REDIS_CONNECT_TIMEOUT` | `5000` | 连接建立超时（毫秒） |
| `socket-timeout-ms` | `EDPA_REDIS_SOCKET_TIMEOUT` | `10000` | Socket 读写超时（毫秒） |
| `checkpointer-ttl-minutes` | `EDPA_REDIS_CHECKPOINTER_TTL` | `60` | Checkpoint TTL（分钟） |

### 1.2 TodoStore 参数

| 参数 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `todo.key-prefix` | `EDPA_REDIS_KEY_PREFIX` | `edpa` | Redis Key 前缀，多实例隔离用 |
| `todo.ttl-seconds` | `EDPA_REDIS_TODO_TTL` | `3600` | Todo 数据 TTL（秒），默认 1 小时 |
| `todo.refresh-on-read` | `EDPA_REDIS_REFRESH_ON_READ` | `true` | 读取时是否自动续期 TTL |

### 1.3 哨兵模式参数（sentinel）

| 参数 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `sentinel.master` | `EDPA_REDIS_SENTINEL_MASTER` | — | 主节点名（如 `mymaster`） |
| `sentinel.nodes` | `EDPA_REDIS_SENTINEL_NODES` | — | 哨兵节点列表，逗号分隔（如 `host1:26379,host2:26379`） |
| `sentinel.password` | `EDPA_REDIS_SENTINEL_PASSWORD` | — | 哨兵认证密码（可选） |

### 1.4 集群模式参数（cluster）

| 参数 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `cluster.nodes` | `EDPA_REDIS_CLUSTER_NODES` | — | 集群节点列表，逗号分隔（如 `host1:6379,host2:6379`） |
| `cluster.max-redirects` | `EDPA_REDIS_CLUSTER_MAX_REDIRECTS` | `3` | 最大重定向次数 |

---

## 2. 部署模式配置

### 2.1 单机模式（single）

默认模式，直接连接单个 Redis 实例。

**环境变量方式**：

```bash
EDPA_REDIS_MODE=single
EDPA_REDIS_HOST=redis-server
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=your-password
EDPA_REDIS_DB=0
```

**YAML 方式**（application.yml）：

```yaml
edpa:
  agent:
    redis:
      mode: single
      host: redis-server
      port: 6379
      password: ${EDPA_REDIS_PASSWORD:}
      database: 0
```

适用场景：开发环境、测试环境、单实例部署。

### 2.2 哨兵模式（sentinel）

通过 Sentinel 节点自动发现 Redis 主节点，支持主节点故障自动切换。

**YAML 方式**：

```yaml
edpa:
  agent:
    redis:
      mode: sentinel
      password: your-redis-password
      database: 0
      sentinel:
        master: mymaster
        nodes:
          - sentinel1.example.com:26379
          - sentinel2.example.com:26379
          - sentinel3.example.com:26379
        password: your-sentinel-password
```

**环境变量方式**：

```bash
EDPA_REDIS_MODE=sentinel
EDPA_REDIS_PASSWORD=your-redis-password
EDPA_REDIS_SENTINEL_MASTER=mymaster
EDPA_REDIS_SENTINEL_NODES=sentinel1.example.com:26379,sentinel2.example.com:26379,sentinel3.example.com:26379
```

适用场景：生产环境推荐，高可用自动故障转移。

### 2.3 集群模式（cluster）

连接 Redis Cluster，客户端自动发现集群节点并路由请求。

**YAML 方式**：

```yaml
edpa:
  agent:
    redis:
      mode: cluster
      password: your-redis-password
      cluster:
        nodes:
          - redis1.example.com:6379
          - redis2.example.com:6379
          - redis3.example.com:6379
        max-redirects: 3
```

**环境变量方式**：

```bash
EDPA_REDIS_MODE=cluster
EDPA_REDIS_PASSWORD=your-redis-password
EDPA_REDIS_CLUSTER_NODES=redis1.example.com:6379,redis2.example.com:6379,redis3.example.com:6379
```

适用场景：大规模部署、高并发场景。

---

## 3. Key 格式与命名规范

EDPAgent 在 Redis 中使用以下 Key 格式：

| Key 格式 | 说明 | TTL | 续期策略 |
|----------|------|-----|---------|
| `{prefix}:todo:{rawSessionId}` | Todo 列表数据 | 3600 秒 | 读时续期 |
| `{prefix}:checkpoint:{rawSessionId}` | 会话 Checkpoint | 60 分钟 | 读时续期 |

**命名规范**：
- `prefix`：通过 `EDPA_REDIS_KEY_PREFIX` 配置，默认 `edpa`
- `rawSessionId`：使用原始 sessionId，**不转义**，支持空格、中文、超长 sessionId
- 集群模式下 Key **不含花括号**（`{}`），避免被 Redis Cluster 解释为 hash tag

**示例**：
```
edpa:todo:state:default:edp_agent:conv-abc-001
edpa:checkpoint:state:default:edp_agent:conv-abc-001
```

---

## 4. TTL 策略

### 4.1 Todo TTL

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `todo.ttl-seconds` | 3600（1 小时） | Todo 数据在 Redis 中的存活时间 |
| `todo.refresh-on-read` | `true` | 每次读取时自动刷新 TTL |

**读时续期流程**：
1. Agent 调用 `todo_list` 或 `todo_modify` 触发 `load()`
2. `RedisTodoStore.load()` 读取数据后执行 `EXPIRE` 命令
3. TTL 重置为 `ttl-seconds`，长会话不会因超时丢失 Todo

**exists 不续期**：`exists()` 方法仅执行 `EXISTS` 命令，不触发 TTL 续期，用于检查是否已规划。

### 4.2 Checkpointer TTL

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `checkpointer-ttl-minutes` | 60（1 小时） | Checkpoint 数据在 Redis 中的存活时间 |
| `refresh-on-read` | `true`（内置） | 读取 Checkpoint 时自动续期 |

Checkpointer TTL 由 `RedisCheckpointer` 内部管理，读时续期为内置行为，不可配置关闭。

---

## 5. 健康检查机制

### 5.1 检查内容

启动时 `RedisTodoStore.healthCheck()` 执行两项检查：

| 检查项 | 命令 | 失败条件 |
|--------|------|---------|
| 连通性 | `PING` | 连接超时、连接拒绝、认证失败 |
| 版本兼容 | `INFO server` | Redis 版本 < 5.0 |

### 5.2 失败行为

健康检查失败时，`RedisTodoStore` Bean 创建抛出 `IllegalStateException`，Spring Boot 容器启动失败。

> **设计决策**：Redis 是 EDPAgent 的核心依赖（Todo + Checkpoint 均依赖 Redis），启动时强制检查可避免运行时才发现 Redis 不可用。如需允许无 Redis 启动，需修改 `RedisConfig` 增加条件加载。

---

## 6. 降级与容错

### 6.1 RedisTodoStore 降级（运行时）

Redis 运行时异常时（连接中断、超时、反序列化失败），`RedisTodoStore` 静默降级，不抛异常：

| 方法 | 降级行为 | 说明 |
|------|---------|------|
| `load(sessionId)` | 返回空列表 | Agent 视为无历史 Todo，重新规划 |
| `save(sessionId, todos)` | 静默失败 | 数据不写入 Redis，记录 ERROR 日志 |
| `exists(sessionId)` | 返回 `false` | Agent 视为未规划 |

日志标记：`[EDPA-DIAG] REDIS_UNAVAILABLE`。

### 6.2 Checkpointer 降级

Checkpointer 启动时注册失败 → 抛 `IllegalStateException` → 容器启动失败。运行时 Checkpoint 保存/加载失败由 Core SDK 内部处理。

### 6.3 Rail 回退

当 `RedisTodoStore` 不可用时（`singletonStore == null`），`EdpaTodoRail` 和 `EdpaEventRail` 回退到文件系统读取 Todo：

```
RedisTodoStore 可用 → 读写 Redis
RedisTodoStore 不可用 → 回退 TodoTool 文件 + TaskPlanningRail 内存缓存
```

> 此回退路径仅在非 Spring 环境（如单元测试）中出现。Spring 环境下 `RedisTodoStore` Bean 必然创建。

---

## 7. 多实例隔离

### 7.1 Key 前缀隔离

多个 EDPAgent 实例共用同一 Redis 时，通过 `key-prefix` 隔离数据：

```bash
# 实例 A
EDPA_REDIS_KEY_PREFIX=edpa-prod

# 实例 B
EDPA_REDIS_KEY_PREFIX=edpa-staging
```

效果：
```
edpa-prod:todo:{sessionId}       # 实例 A 的 Todo
edpa-staging:todo:{sessionId}    # 实例 B 的 Todo（同一 sessionId 不冲突）
```

### 7.2 Database 索引隔离

也可通过 `database` 参数使用不同 Redis 数据库索引隔离：

```bash
# 实例 A
EDPA_REDIS_DB=0

# 实例 B
EDPA_REDIS_DB=1
```

> 集群模式不支持 `database` 参数（Redis Cluster 仅使用 DB 0）。

### 7.3 集群模式 Hash Tag 安全

Key 格式 `{prefix}:todo:{rawSessionId}` 不包含花括号 `{}`，避免 Redis Cluster 将花括号内的内容解释为 hash tag，确保 Key 正确分片。

---

## 8. 代码架构

### 8.1 核心类

| 类 | 模块 | 职责 |
|----|------|------|
| `RedisConfig` | `engine` | Spring `@Configuration`，创建 `LettuceConnectionFactory`、`StringRedisTemplate`、`RedisTodoStore`，注册 `RedisCheckpointer` |
| `TodoRedisProperties` | `engine` | `@ConfigurationProperties(prefix = "edpa.agent.redis")`，承载全部配置参数 |
| `RedisTodoStore` | `engine` | Todo 持久化存储：`save`/`load`/`exists`/`delete`/`healthCheck` |
| `RedisCheckpointer` | `core-sdk` | Core SDK 提供，会话状态 Checkpoint 持久化 |

### 8.2 初始化流程

```
Spring Boot 启动
  │
  ├─ RedisConfig.redisConnectionFactory()
  │    └── 按 mode 创建 LettuceConnectionFactory
  │        ├── single → RedisStandaloneConfiguration
  │        ├── sentinel → RedisSentinelConfiguration
  │        └── cluster → RedisClusterConfiguration
  │        （均强制 RESP2 协议）
  │
  ├─ RedisConfig.initRedisCheckpointer()  @PostConstruct
  │    └── 创建 RedisCheckpointer → 注册为全局默认 Checkpointer
  │
  └─ RedisConfig.redisTodoStore()  @Bean
       ├── new RedisTodoStore(redisTemplate, props)
       ├── store.healthCheck()  ← PING + 版本检查
       └── singletonStore = store  ← 静态持有，供非 Spring 类获取
```

### 8.3 消费方

| 消费方 | 获取方式 | 用途 |
|--------|---------|------|
| `EdpaTodoRail` | `RedisConfig.getRedisTodoStore()` | `hasPlannedTodos()` 检查是否已规划 |
| `EdpaEventRail` | `RedisConfig.getRedisTodoStore()` | `loadCurrentTodos()` 加载当前 Todo 用于事件发射 |
| `EdpaTodoRail.afterToolCall()` | `RedisConfig.getRedisTodoStore()` | `save()` 持久化更新的 Todo |
| DeepAgent 引擎 | `CheckpointerFactory.getDefaultCheckpointer()` | 会话中断恢复 |

---

## 9. RESP2 协议说明

EDPAgent 强制使用 Redis RESP2 协议（`ProtocolVersion.RESP2`），而非 RESP3。

**原因**：
- Lettuce 客户端在 RESP3 模式下与部分 Redis 代理（如 twemproxy、部分云厂商代理）存在兼容性问题
- RESP2 是更广泛兼容的协议版本，覆盖更多 Redis 部署形态
- Spring Data Redis 对 RESP2 的支持更成熟稳定

**配置位置**：`RedisConfig.redisConnectionFactory()` 中通过 `ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2)` 设置，不可通过配置参数关闭。
