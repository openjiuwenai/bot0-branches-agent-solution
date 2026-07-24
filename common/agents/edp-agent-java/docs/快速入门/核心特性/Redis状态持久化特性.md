# Redis 状态持久化特性

## 特性概述

Redis 状态持久化是 EDPAgent 的核心基础设施特性，为 Agent 提供会话级状态存储能力。通过 Redis 统一管理 Todo 任务列表和会话中断恢复点（Checkpoint），使 EDPAgent 支持分布式无状态部署——同一会话可跨实例共享状态，中断后可自动恢复。

核心能力包括：
- **Todo 列表持久化**：Agent 规划的任务列表存储在 Redis 中，跨实例共享，跨会话恢复
- **会话中断恢复**：Agent 执行过程中的状态快照（Checkpoint）持久化到 Redis，中断后从最近 Checkpoint 恢复
- **读时续期**：每次读取 Todo/Checkpoint 时自动刷新 TTL，避免长会话中途过期导致状态丢失
- **多实例隔离**：通过 Key 前缀隔离不同 EDPAgent 实例，支持多实例共用同一 Redis

---

## 核心能力

### 1. Todo 列表持久化（RedisTodoStore）

Agent 通过 `todo_create`、`todo_modify` 等工具管理任务列表时，任务数据自动持久化到 Redis。

| 操作 | Redis 命令 | Key | 说明 |
|------|-----------|-----|------|
| 创建/修改 Todo | `SET` + `EXPIRE` | `{prefix}:todo:{sessionId}` | 写入任务列表 JSON，刷新 TTL |
| 读取 Todo | `GET` + `EXPIRE` | `{prefix}:todo:{sessionId}` | 读取任务列表，读时续期 |
| 检查是否存在 | `EXISTS` | `{prefix}:todo:{sessionId}` | 不触发 TTL 续期 |
| 删除 Todo | `DEL` | `{prefix}:todo:{sessionId}` | 会话结束时清理 |

### 2. 会话中断恢复（RedisCheckpointer）

Agent 执行过程中，DeepAgent 引擎定期将执行状态快照保存为 Checkpoint。会话中断后，引擎从 Redis 中最近的 Checkpoint 恢复，继续执行未完成的任务。

| 操作 | 说明 |
|------|------|
| 保存 Checkpoint | 引擎在关键节点自动保存执行状态到 Redis |
| 恢复 Checkpoint | 会话重连时从 Redis 加载最近 Checkpoint |
| TTL 管理 | 默认 60 分钟，读时自动续期 |

---

## 架构示意

```
┌─────────────────────────────────────────────────────────────┐
│                      EDPAgent 启动流程                       │
│                                                             │
│  Spring Boot 启动                                           │
│       │                                                     │
│       ▼                                                     │
│  RedisConfig（@Configuration）                               │
│       │                                                     │
│       ├── 1. 创建 LettuceConnectionFactory                   │
│       │      （RESP2 强制 + 连接超时 + 三模式分发）           │
│       │                                                     │
│       ├── 2. @PostConstruct initRedisCheckpointer()         │
│       │      └── 注册 RedisCheckpointer 为全局默认           │
│       │          （Core SDK 会话状态持久化到 Redis）          │
│       │                                                     │
│       └── 3. @Bean redisTodoStore()                         │
│              ├── new RedisTodoStore(redisTemplate, props)   │
│              ├── store.healthCheck()  ← PING + 版本检查      │
│              └── singletonStore = store                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
              │                              │
              ▼                              ▼
┌─────────────────────────┐    ┌─────────────────────────────┐
│    RedisTodoStore        │    │    RedisCheckpointer          │
│                          │    │                              │
│  save(sessionId, todos)  │    │  save(sessionId, checkpoint) │
│  load(sessionId)         │    │  load(sessionId)             │
│  exists(sessionId)       │    │                              │
│  delete(sessionId)       │    │                              │
└───────────┬─────────────┘    └──────────────┬──────────────┘
            │                                  │
            ▼                                  ▼
     EdpaTodoRail                      DeepAgent 引擎
     EdpaEventRail                     （会话状态管理）
            │                                  │
            └──────────┬───────────────────────┘
                       │
                       ▼
              ┌────────────────┐
              │     Redis      │
              │  (>= 5.0)      │
              │                │
              │  {prefix}:todo:{sessionId}       │
              │  {prefix}:checkpoint:{sessionId} │
              └────────────────┘
```

---

## 核心收益

| 收益 | 说明 |
|------|------|
| 分布式无状态部署 | Agent 实例无本地状态，可水平扩缩容，请求路由到任意实例均可继续会话 |
| 跨实例会话共享 | 同一会话的 Todo 和 Checkpoint 在所有实例间共享，无需会话亲和 |
| 中断自动恢复 | 会话中断后重连，引擎从最近 Checkpoint 恢复，Todo 状态延续 |
| 多实例隔离 | 通过 `key-prefix` 隔离不同 EDPAgent 部署，共用同一 Redis 集群 |

---

## 快速配置

最小配置（单机无密码）：

```bash
EDPA_REDIS_HOST=localhost
EDPA_REDIS_PORT=6379
```

生产配置（带密码）：

```bash
EDPA_REDIS_HOST=redis.example.com
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=your-password
```

Docker 连接宿主机 Redis：

```bash
EDPA_REDIS_HOST=host.docker.internal
```

> Redis 版本要求 >= 5.0。启动时自动执行健康检查（PING + 版本检查），不达标则容器启动失败。

完整配置参数见 [Redis 集成指南](../developer-guide/Redis集成指南.md)。

---

## 相关文档

- [Redis 集成指南](../../开发指南/Redis集成指南.md) - 完整配置参数、三模式部署、Key 规范、降级行为
- [环境变量参考](../../参考指南/环境变量参考.md) - Redis 相关环境变量列表
- [任务规划特性](任务规划特性.md) - Todo 状态机与任务管理
