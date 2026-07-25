# Redis 集成指南

## 1. 配置前缀与参数表

Redis 配置前缀为 `edpa.agent.redis`，对应类 `TodoRedisProperties`（仅承载连接参数与 Checkpointer TTL，不再包含 Todo 子配置）。

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

### 1.2 哨兵模式参数（sentinel）

| 参数 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| `sentinel.master` | `EDPA_REDIS_SENTINEL_MASTER` | — | 主节点名（如 `mymaster`） |
| `sentinel.nodes` | `EDPA_REDIS_SENTINEL_NODES` | — | 哨兵节点列表，逗号分隔（如 `host1:26379,host2:26379`） |
| `sentinel.password` | `EDPA_REDIS_SENTINEL_PASSWORD` | — | 哨兵认证密码（可选） |

### 1.3 集群模式参数（cluster）

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

| Key 格式 | 管理方 | 说明 | TTL |
|----------|--------|------|-----|
| `{rawSessionId}:todo` | agent-core `KvTodoStorage`（通过 `DeepAgentConfig.kvStoreConfig` 配置） | Todo 列表数据 | 由 agent-core KV 存储管理 |
| `edpa:toolcount:{sessionId}` | `ExecutionLimitRail`（常量 `REDIS_KEY_PREFIX="edpa"`） | 工具调用计数 | 3600 秒（常量 `TOOL_COUNT_TTL_SECONDS=3600L`） |
| `{sessionId}:agent:{agentId}:agent_state_blobs` | Core SDK `RedisCheckpointer` | 会话 Checkpoint | 60 分钟（`checkpointer-ttl-minutes`） |

**命名规范**：
- `rawSessionId`：Todo key 中的 sessionId 由 `KvTodoStorage` 管理，使用转义后的 sessionId（`TodoSessionResolver.sanitizeSessionId`）
- `sessionId`：工具调用计数 key 中的 sessionId 为原始值，由 `ExecutionLimitRail.resolveSid(ctx)` 解析
- `edpa` 前缀：工具调用计数使用代码常量 `REDIS_KEY_PREFIX="edpa"`，**不可通过配置修改**；Todo key 不再包含前缀
- 集群模式下 Key **不含花括号**（`{}`），避免被 Redis Cluster 解释为 hash tag

**示例**：
```
conv-abc-001:todo                              # Todo 列表（agent-core KvTodoStorage）
edpa:toolcount:conv-abc-001                    # 工具调用计数（ExecutionLimitRail）
conv-abc-001:agent:EDPAgent:agent_state_blobs  # Checkpoint（RedisCheckpointer）
```

---

## 4. TTL 策略

### 4.1 Todo TTL（agent-core 管理）

Todo 数据的持久化与 TTL 由 agent-core 的 `TodoStorage` SPI 统一管理：

- 当 `DeepAgentConfig.todoStorageType="kv"` 且 `kvStoreConfig` 指向 Redis 时，Todo 通过 `KvTodoStorage` 写入 Redis（key 格式 `{rawSessionId}:todo`）
- 当 `kvStoreConfig` 为 null（Redis 未配置）时，`todoStorageType` 回落到 `"file"`，Todo 写入本地文件系统（`FileTodoStorage`）
- TTL、读时续期等行为由 agent-core KV 存储内部管理，EDPAgent 不再自定义 `todo.ttl-seconds` / `todo.refresh-on-read` 配置项

> **变更说明**：原 `RedisTodoStore` 已删除，Todo 持久化改为 agent-core 的 `TodoStorage` SPI，通过 `DeepAgentConfig` 的 `todoStorageType` / `kvStoreConfig` 配置。`TodoStorage` 接口无 `exists` 方法，`EdpaTodoRail.hasPlannedTodos()` 改为 `load` 后判空。

### 4.2 Checkpointer TTL

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `checkpointer-ttl-minutes` | 60（1 小时） | Checkpoint 数据在 Redis 中的存活时间 |
| `refresh-on-read` | `true`（内置） | 读取 Checkpoint 时自动续期 |

Checkpointer TTL 由 `RedisCheckpointer` 内部管理，读时续期为内置行为，不可配置关闭。

---

## 5. 启动校验机制

### 5.1 校验内容

Redis 已不再通过自定义 `RedisTodoStore.healthCheck()` 进行独立的 PING + 版本检查。当前启动校验依赖以下两条路径：

| 校验路径 | 触发点 | 失败条件 |
|----------|--------|---------|
| `LettuceConnectionFactory` 初始化 | `RedisConfig.redisConnectionFactory()` Bean 创建 | 连接参数错误、`ClientOptions` 构建失败 |
| `RedisCheckpointer` 注册 | `RedisConfig.initRedisCheckpointer()` `@PostConstruct` | Redis URL 不可达、Checkpointer Provider 创建抛 `IllegalStateException` / `IllegalArgumentException` |

### 5.2 失败行为

`initRedisCheckpointer()` 注册失败时抛 `IllegalStateException`（"Redis Checkpointer registration failed"），Spring Boot 容器启动失败。

> **设计决策**：Todo 持久化已下沉至 agent-core 的 `TodoStorage` SPI，`kvStoreConfig` 为 null 时回落到 `FileTodoStorage`，因此 EDPAgent 不再强制要求 Redis 可用即可启动；但 Checkpointer 仍依赖 Redis，注册失败会导致容器启动失败。

---

## 6. 降级与容错

### 6.1 TodoStorage 降级（运行时）

Todo 持久化由 agent-core 的 `TodoStorage` SPI 承载，`EdpaTodoRail` / `EdpaEventRail` 通过 `getTodoStorage()` 按以下顺序回落：

| 顺序 | 条件 | 存储实现 | 数据位置 |
|------|------|---------|---------|
| 1 | `deepAgent.getKvStore()` 非 null | `KvTodoStorage` | Redis（key: `{rawSessionId}:todo`） |
| 2 | `kvStore` 为 null 但 workspace 可用 | `FileTodoStorage` | 本地 `.todo/` 目录 |
| 3 | workspace 不可用 | 返回 null | `EdpaTodoRail` 回落到 `TaskPlanningRail.cachedTodos` 内存缓存 |

> **变更说明**：原 `RedisTodoStore` 的 `load` / `save` / `exists` 静默降级行为已由 agent-core `TodoStorage` SPI 与 `FileTodoStorage` 回落路径替代。`TodoStorage` 接口无 `exists` 方法，`EdpaTodoRail.hasPlannedTodos()` 改为 `load` 后判空。

### 6.2 Checkpointer 降级

Checkpointer 启动时注册失败 → 抛 `IllegalStateException` → 容器启动失败。运行时 Checkpoint 保存/加载失败由 Core SDK 内部处理。

### 6.3 Rail 回退

当 `deepAgent.getKvStore()` 返回 null 时（Redis 未配置或 agent-core 未初始化 KV 存储），`EdpaTodoRail` 和 `EdpaEventRail` 回退到 `FileTodoStorage`：

```
kvStore 可用（Redis 已配置） → KvTodoStorage 读写 Redis
kvStore 为 null             → FileTodoStorage 读写本地 .todo/ 目录
workspace 不可用             → 返回 null，hasPlannedTodos 回落 TaskPlanningRail 缓存
```

> 此回退路径在 Redis 未配置（`kvStoreConfig` 为 null，`todoStorageType="file"`）或非 Spring 环境（单元测试 mock `DeepAgent` 无 workspace）时出现。

---

## 7. 多实例隔离

### 7.1 隔离方式

原 `EDPA_REDIS_KEY_PREFIX` 环境变量已删除，Todo key 不再支持可配置前缀（由 agent-core `KvTodoStorage` 管理为 `{rawSessionId}:todo`）。工具调用计数 key 的前缀 `edpa` 为代码常量（`REDIS_KEY_PREFIX="edpa"`），亦不可通过配置修改。

多实例共用同一 Redis 时，推荐通过 `database` 索引隔离（见 7.2），或部署独立 Redis 实例。

### 7.2 Database 索引隔离

通过 `database` 参数使用不同 Redis 数据库索引隔离：

```bash
# 实例 A
EDPA_REDIS_DB=0

# 实例 B
EDPA_REDIS_DB=1
```

> 集群模式不支持 `database` 参数（Redis Cluster 仅使用 DB 0）。

### 7.3 集群模式 Hash Tag 安全

Todo key（`{rawSessionId}:todo`）与工具调用计数 key（`edpa:toolcount:{sessionId}`）均不包含花括号 `{}`，避免 Redis Cluster 将花括号内的内容解释为 hash tag，确保 Key 正确分片。

---

## 8. 代码架构

### 8.1 核心类

| 类 | 模块 | 职责 |
|----|------|------|
| `RedisConfig` | `engine` | Spring `@Configuration`，创建 `LettuceConnectionFactory`、`StringRedisTemplate`，注册 `RedisCheckpointer`（`initRedisCheckpointer`）；静态暴露 `getStringRedisTemplate()` / `getRedisProperties()`。**不再创建 `RedisTodoStore` Bean** |
| `TodoRedisProperties` | `engine` | `@ConfigurationProperties(prefix = "edpa.agent.redis")`，承载连接参数与 `checkpointer-ttl-minutes`（不含 Todo 子配置） |
| `EdpaExtHandler` | `engine` | `buildKvStoreConfig()` 从 `RedisConfig.getRedisProperties()` 构建 `kvStoreConfig`（`type=redis, conf={host,port,password,cluster}`），通过 `DeepAgentConfig.todoStorageType` / `kvStoreConfig` 传给 agent-core |
| `KvTodoStorage` / `FileTodoStorage` | `core-sdk` | agent-core 的 `TodoStorage` SPI 实现：`KvTodoStorage` 走 Redis，`FileTodoStorage` 走文件 |
| `ExecutionLimitRail` | `engine` | 工具调用计数持久化，常量 `REDIS_KEY_PREFIX="edpa"`、`TOOL_COUNT_TTL_SECONDS=3600L`，通过 `RedisConfig.getStringRedisTemplate()` 读写 `edpa:toolcount:{sessionId}` |
| `RedisCheckpointer` | `core-sdk` | Core SDK 提供，会话状态 Checkpoint 持久化 |

### 8.2 初始化流程

```
Spring Boot 启动
  │
  ├─ RedisConfig 构造（singletonProps 赋值，供 EdpaExtHandler.buildKvStoreConfig 取用）
  │
  ├─ RedisConfig.redisConnectionFactory()  @Bean
  │    └── 按 mode 创建 LettuceConnectionFactory（RESP2 强制）
  │        ├── single → RedisStandaloneConfiguration
  │        ├── sentinel → RedisSentinelConfiguration
  │        └── cluster → RedisClusterConfiguration
  │
  ├─ RedisConfig.initRedisCheckpointer()  @PostConstruct
  │    └── 创建 RedisCheckpointer → CheckpointerFactory.setDefaultCheckpointer()
  │
  └─ RedisConfig.stringRedisTemplate()  @Bean
       └── singletonTemplate 赋值，供 ExecutionLimitRail 取用

EdpaExtHandler.performInit（DeepAgent 构造阶段）
  │
  ├─ buildKvStoreConfig()
  │    └── 从 RedisConfig.getRedisProperties() 取 TodoRedisProperties
  │        构建 {type=redis, conf={host,port,password,cluster}}，返回 kvStoreConfig
  │        （Redis 未配置时返回 null）
  │
  └─ buildDeepAgentConfig()
       └── DeepAgentConfig.todoStorageType = kvStoreConfig != null ? "kv" : "file"
           kvStoreConfig 为 null 时 agent-core 回落 FileTodoStorage
```

### 8.3 消费方

| 消费方 | 获取方式 | 用途 |
|--------|---------|------|
| `EdpaTodoRail` / `EdpaEventRail` | `deepAgent.getKvStore()` → `KvTodoStorage`（为 null 时 `FileTodoStorage`） | Todo 读写：`hasPlannedTodos()` / `loadCurrentTodos()` / `save()` |
| `EdpaTodoRail` 兜底 | `TaskPlanningRail.cachedTodos(sid)` | `TodoStorage` 不可用时读缓存 |
| `ExecutionLimitRail` | `RedisConfig.getStringRedisTemplate()` | 工具调用计数 `edpa:toolcount:{sessionId}` 恢复 / 持久化 |
| DeepAgent 引擎 | `CheckpointerFactory.getDefaultCheckpointer()` | 会话中断恢复 |

---

## 9. RESP2 协议说明

EDPAgent 强制使用 Redis RESP2 协议（`ProtocolVersion.RESP2`），而非 RESP3。

**原因**：
- Lettuce 客户端在 RESP3 模式下与部分 Redis 代理（如 twemproxy、部分云厂商代理）存在兼容性问题
- RESP2 是更广泛兼容的协议版本，覆盖更多 Redis 部署形态
- Spring Data Redis 对 RESP2 的支持更成熟稳定

**配置位置**：`RedisConfig.redisConnectionFactory()` 中通过 `ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2)` 设置，不可通过配置参数关闭。

---

## 10. 运行中 Redis 停止的降级行为

### 影响与降级

EDPAgent 运行过程中 Redis 停止时，各组件的降级行为：

| 组件 | 降级行为 | 日志关键词 |
|------|---------|-----------|
| A2A TaskStore | 持久化失败，请求返回 500 `TaskStore persistence failed` | `[A2A-Bridge] blocking request failed` |
| Todolist (KvTodoStorage) | 回落到 FileTodoStorage | `getTodoStorage: workspace unavailable` |
| 工具调用计数 (ExecutionLimitRail) | 计数从 0 开始，持久化失败不阻断 | `[ExecutionLimitRail] Redis GET failed` |
| Checkpointer | 运行时由 agent-core 内部处理 | 无 EDPAgent 日志 |
| lettuce 连接 | ConnectionWatchdog 自动重连 | `Cannot reconnect to` |

### 关键说明

- **TaskStore** 由 A2A SDK（`org.a2aproject.sdk:a2a-java-sdk-server-common`）的 `RedisTaskStore` 实现，EDPAgent 不直接管理。Redis 停止时 `TaskStore.save()` 失败导致 `TaskStore persistence failed`。
- **Todolist** 由 agent-core 的 `KvTodoStorage` 实现，Redis 停止时 `EdpaTodoRail.getTodoStorage()` 回落到 `FileTodoStorage`。
- **工具调用计数** 由 `ExecutionLimitRail` 管理，Redis GET 失败时从 0 开始，Redis SET 失败时静默降级（不影响业务）。
- **lettuce 自动重连**：Redis 恢复后 lettuce ConnectionWatchdog 自动重连，无需重启 EDPAgent。
