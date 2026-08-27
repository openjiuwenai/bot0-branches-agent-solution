# 配置参考

面向**部署方**：本 runtime 的全部配置项、它们归谁管、以及不属于本 runtime 的那些。

本文只做归集，每一条的事实源在对应的 L2 详设（`internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/`），与详设冲突时以详设为准。

---

## 1. 配置归属

### 1.1 唯一命名空间

**本 runtime 的全部配置落在 `openjiuwen.service.*` 下，没有第二个命名空间。**

这与本平台 Java 版 runtime 同一体系——Java 侧的 `@ConfigurationProperties` 全部挂在同一前缀下，运维读一份配置能同时看懂两个 runtime。该约束由 `tools/config_namespace_guard.py` 守住，写进别的根键即阻断。

### 1.2 谁读配置

**SDK 自己不去找配置文件。** 本 runtime 嵌在宿主进程里，去哪找配置、读不读、读几份，是宿主的决定。宿主用 `ConfigLoader` 加载后把结果传进装配函数，或直接构造各组件的配置对象。

SDK 自己翻文件系统是一种未经宿主同意的默认行为，本设计不做。

### 1.3 环境变量

`ConfigLoader` 始终读环境变量，即使调用方没显式列它——部署方需要一个无需改调用代码就能覆盖任何字段的入口。

格式：`前缀__A__B=值` 对应字段路径 `a.b`。**层级分隔是双下划线**，因为字段名本身常含单下划线（`max_connections`），用单个会把它切成两层。

前缀是 `OPENJIUWEN__SERVICE`，由文件命名空间 `openjiuwen.service` 推出：同一个配置项写文件叫 `openjiuwen.service.middleware.standalone.host`，写环境变量叫 `OPENJIUWEN__SERVICE__MIDDLEWARE__STANDALONE__HOST`，读者只记一套名字。旧前缀 `RUNTIME__*` 仍被读取并逐键告警指出新名字，两边都配时以新前缀为准。

可直接照抄的样例在 `deploy/.env.example`：第二部分列出参考宿主从环境变量读取的各段（生命周期、扩展点、凭据解密、状态缓存、技能中心），每一项都由判据核对能绑到配置字段并读回样例值；第一部分是参考宿主自己的旋钮（`RUNTIME_*`，单下划线，不是 runtime 配置项）；第三部分是从存量升级时每一项的去向。

> **已知误报**：参考宿主按段分别绑定（`RuntimeConfig`、状态缓存段、技能中心段各绑一次），绑 `RuntimeConfig` 时会把同一前缀下的 `middleware`、`skill_hub` 报为「未声明的键，已忽略」。它们实际由各自的段读取、取值生效，该告警不表示配置丢失。已登记待修。

---

## 2. 配置项全表

共九段、59 项。每段末尾标注它属哪个特性，展开说明去那份详设的 `§6 配置模型`。

### 2.1 `openjiuwen.service.lifecycle` — 生命周期

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `init_fail_fast` | `true` | 初始化失败时终止启动；设为 `false` 则降级启动 |
| `shutdown_timeout_s` | `30` | 关停宽限秒数 |

关停失败一律不中断回收。事实源：`Feat-Func-000b`。

### 2.2 `openjiuwen.service.extensions` — 扩展点装配

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `handler.impl` | `""` | 按名指定执行处理器实现；留空则按优先级自动选取 |
| `cache.impl` | `""` | 同上，缓存扩展点 |

**当前生产装配不消费本段**——配置读得到，但没有装配路径按它选实现（问题台账「配置段未接线」已登记待修），配了不生效。键名即扩展点名，与入口点分组名拼接。**部署中装了多个适配器包时建议显式指定**——自动选取虽有确定规则，但「装了什么」会随打包变化。事实源：`Feat-Func-000b`。

### 2.3 `openjiuwen.service.credential` — 凭据解密

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `decryptor` | `""` | 解密器实现指向；未装配时配置值原样返回 |

事实源：`Feat-Func-000b`。

### 2.4 `openjiuwen.service.adapter` — 适配器选取

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `name` | `""` | 按标识精确指定；留空则按优先级自动选取 |
| `allow-auto-select` | `true` | 设为 `false` 则发现到多实现直接启动失败 |

**一个实例只服务一个 Agent**，此处选一个，不是配一组。事实源：`Feat-Func-002b`。

### 2.5 `openjiuwen.service.middleware` — 状态缓存

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `endpoint_type` | `standalone` | `standalone` 或 `cluster`；未配置时按单机处理，既有单机配置无需改动 |
| `checkpointer.ttl_seconds` | `604800` | 统一过期时间（7 天），Task 快照与回调配置同源。**所有写入一律带过期，无例外键**。属性名对齐上游 `openjiuwen.service.middleware.checkpointer.ttl-seconds`；存量 `REDIS_SESSION_TTL` 经环境桥落到这里 |
| `standalone.host` / `.port` / `.database` / `.timeout_ms` | `127.0.0.1` / `6379` / `0` / `3000` | 单机连接参数；集群形态下本节整体忽略 |
| `standalone.encrypted_password` | `${REDIS_PASSWORD_ENCRYPTED:}` | 配置文件里放密文，由解密接缝填充 |
| `cluster.nodes` | — | 集群节点，必填，至少一个；推荐多个以避免单点入口失效 |
| `cluster.timeout_ms` / `.encrypted_password` | `3000` / 同上 | 集群连接参数 |
| `retry.max_attempts` / `.base_delay_s` / `.max_delay_s` | `3` / `0.05` / `1.0` | 韧性重试参数 |

`cluster` 形态下 `database` 被忽略且有诊断提示。事实源：`Feat-Func-003b`，过期时间归属见 `Feat-Func-008b` 与 `Feat-Func-009b`。

### 2.5b `openjiuwen.service.runtime_db` — Task 快照的数据库档

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `runtime_db_enabled` | `false` | 关：Task 快照纯 Redis（默认形态）。开：快照落进「缓存 + 数据库」两层存储，Redis 失效后仍在库里 |
| `runtime_db_type` | 空 | `sqlite`／`postgres`／`gaussdb`，开启时必填 |
| `runtime_db_host` | 空 | 非 sqlite 形态必填 |
| `runtime_db_port` | 空 | 同上 |
| `runtime_db_name` | 空 | 库名 |
| `runtime_db_user` | 空 | 同上 |
| `runtime_db_password` | 空 | **不落配置文件**，由部署方经环境变量提供 |
| `runtime_db_sqlite_path` | 空 | sqlite 形态的文件路径 |

**字段名逐字取存量**（`runtime_db_*`），故属性路径读起来有一层重复（`runtime_db.runtime_db_enabled`）。这是刻意的：装配层按这些名字逐个取值，改名会让它读不到而静默退回默认档，且宿主从存量迁过来的 `.env` 也就此失效。

**两条来源都认**：本段可写在配置文件里，也可由存量的 `RUNTIME_DB_*` 环境变量给出——从存量升级的宿主把 `.env` 原样迁过来即生效。文件里写了非默认值时以文件为准，环境变量只补文件没写的项。

**开启时宿主须挂初始化钩子**：取 `build_a2a_stores_with_init` 的第三个返回值，放进组合根的 `init_hooks`。这一档要在启动期连库建表；漏挂时存储在第一次使用时报可诊断的错，不会静默退化成不落库。

**已知限制**：本档下按会话列举任务返回空——存量同形。REST 绑定件的会话寻址因此退回进程内表。纯 Redis 档有会话索引，不受影响。

### 2.6 `openjiuwen.service.versatile` — 远端服务代理

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `url-template` | — | **必填**。远端入口地址，支持 `{conversation_id}` 占位 |
| `timeout-s` | `600` | 单次调用整体超时。远端工作流可能长时间运行，短超时会把正常长任务误判为失败 |
| `headers-template` | `Accept`、`stream` 两项 | 出站头模板，取自存量实际报文 |
| `forward-header-whitelist` | `[]` | **空即转发全部**，与存量语义一致 |
| `result-extraction.node-name` / `.node-type` | `answer` / `QA` | 从远端帧里取终答的定位 |
| `verify-tls` | `false` | **取存量默认以保对外兼容**，待验证环境确认远端使用正规 CA 后翻转 |
| `drop-sse-field-lines` | `false` | 非 JSON 行是否丢弃。默认透传，与存量一致 |

**本段的键名由权威接口表逐字规定**（含连字符命名）。事实源：`Feat-Func-002b`《远端服务代理》。

### 2.7 `openjiuwen.service.bus` — 总线消费

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 总开关。关闭时不订阅、不消费、不发布 |
| `tenant_id` / `service_id` | — | 租户与服务标识 |
| `max_in_flight` | `32` | 并发消费上限 |
| `schema_major` | `1` | 事件 schema 主版本 |
| `admission.capacity` | `4096` | 准入表容量 |
| `stream_reference.ttl_s` | `3600` | 流引用有效期 |

事实源：`Feat-Func-017b`。

### 2.8 `openjiuwen.service.remote` — 远端事件投影

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `projection.agent_event.enabled` | `true` | 是否投影远端 Agent 事件 |
| `projection.agent_event.local_agent_id` | `""` | 本地 Agent 标识，用于生产者标签 |

事实源：`Feat-Func-027b`。

### 2.8b `openjiuwen.service.a2a.remote_invocation` — 远端委派上限与开关

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `max_concurrency` | `3` | 每次派发的并发预算；超出且队列满则进跳过清单（取存量默认，上游为 16） |
| `max_queue_size` | `0` | 等待发起的委托数上限；`0` 即排队层关闭（取存量既有对外行为；取舍见问题台账 P2-04b） |
| `max_call_depth` | `3` | 下游再发起下游调用的层数上限（取存量默认；上游无此项） |
| `cancel_on_failure` | `true` | 失败终态是否向远端传播取消（我方自补的出站动作，可独立关掉） |

段名对齐上游 `openjiuwen.service.a2a.remote-invocation.*`。显式传给 `build_remote_batch_runner` 的形参优先于本段。存量 `MAX_CALL_DEPTH`／`MAX_CONCURRENT_SUB_AGENTS` 经环境桥落到本段。事实源：`Feat-Func-004b` §6.2。

### 2.9 `openjiuwen.service.skill-hub` — 技能中心

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | **总开关，默认关闭**——不配置时整条链路不装配，runtime 行为与没有本特性时逐字相同 |
| `endpoint` | — | 技能中心地址 |
| `auth_type` / `encrypted_token` | `bearer` / `${SKILLHUB_TOKEN_ENCRYPTED:}` | 认证方式与密文令牌 |
| `local_dir` | `/var/lib/agent-runtime/skills` | 技能包落盘目录 |
| `provider` | `""` | 自定义提供方实现指向 |
| `fetch.concurrency` / `.page_size` | `4` / `200` | 拉取并发与分页 |
| `fetch.connect_timeout_s` / `.request_timeout_s` / `.download_timeout_s` | `10.0` / `30.0` / `600.0` | 三档超时 |
| `fetch.max_extracted_bytes` | `536870912` | 解包体积上限（512 MiB） |
| `retry.max_attempts` / `.initial_delay_s` / `.period_s` | `120` / `5.0` / `30.0` | 后台重试参数 |

事实源：`Feat-Func-005b`。

### 2.10 `openjiuwen.service.state_store` — 存量兼容状态存储

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 未使能时不构造存储、不连数据库 |
| `backend` | `sqlite` | `sqlite` 或 `gaussdb` |
| `table_name` | `runtime_kv_state` | 表名 |
| `default_ttl_seconds` | `604800` | 默认过期 |
| `cache_key_prefix` | `runtime` | 键前缀 |
| `dsn` | `""` | 由部署方提供；**口令不落配置文件** |

本段服务于存量兼容外观，不是交付面。事实源：`L2-legacy-internal-api-compat.md`。

---

## 3. 不属于本 runtime 的配置

以下配置**不由本 runtime 定义、不由本 runtime 读取**，写在这里是为了让部署方知道该去哪调：

| 配置 | 归谁 |
|---|---|
| 模型端点、密钥、推理参数 | 宿主的 Agent 框架 |
| 日志级别与输出目标 | 宿主的日志设施 |
| 服务监听地址与端口、进程数 | 宿主的 ASGI 服务器 |
| 部署编排、容器资源、副本数 | 平台 |
| DeepAgent Todolist 的 Redis 键过期 | 外部 Redis 连接池或运维侧统一管理（上位规格明写 runtime 不自行设置） |

---

## 4. 相关文档

- 《入口与数据契约》`doc/entrypoints-and-contracts.md`
- 《集成指南》`doc/integration-guide.md`
- 各特性详设的 `§6 配置模型`
