# 从 a2a_service 升级到本版

面向**部署方**：手里有一套跑着的 `applications/a2a_service`，要换成本版 runtime。

本文只做归集，每一条的事实源在 `internal/operate/ENV-MAPPING-FROM-A2A-SERVICE.md` 与对应的 L2 详设，冲突时以详设为准。

---

## 1. 三步走

```bash
# ① 装本版
pip install -e .

# ② 启动命令只改模块名
#    存量：python main.py
python -m agent_runtime.bootstrap.legacy_compat

# ③ 看启动日志里的告警——它们说明哪几项配置不生效
```

`.env` 原样保留，两份配置文件原样保留，**你的 Agent 代码原位不动**。**改的只有启动命令那一行。**

起来的是你的 Agent：兼容入口默认按存量的导入名 `agents.EDPAgent` 装载它（与存量 `app.py` 的
`from agents.EDPAgent import initialize` 同一导入名、同一落位 `applications/a2a_service/agents/EDPAgent/`），
启动阶段调一次 `initialize()`，每次请求以存量执行器相同的实参调 `agent_stream(query=, conv_id=, cascade_result=, context=)`。
细节与两种接入方式见《EDPAgent 在本版上的部署》`doc/deploy-edpagent.md`。

存量 `.env.example` 每一项的去向也以可直接比对的形式列在 `deploy/.env.example` 第三部分：分组与本文第 2 节一致，取值逐字取自存量出厂值，两者都由判据核对。

这一条由两条部署级端到端验证守着：`deploy-e2e/run-legacy-env-parity.sh` 拿存量 `.env.example` 原样起本版，验它起得来、能对外服务、且告警分界正确；`deploy-e2e/run-legacy-edpagent.sh` 以同样的环境起**真 EDPAgent**，验同步与流式请求真的由它应答（需模型凭据与 Redis）。

---

## 2. 你的配置去了哪

存量 `.env.example` 共 130 行，但**其中 82 项 `a2a_service` 自己并不读**——`DPA_MEMORY_*`、`PLANNING_AGENT_MODEL_*`、`LLM_*`、`MCP_*`、`OTEL_*` 这些属宿主 Agent 与可观测设施，**与换不换 runtime 无关，原样保留即可**。

真正被 `a2a_service` 承接的是 49 项，去向如下。

### 2.1 直接生效（19 项）

Redis 连接与过期、远端代理地址与超时、数据库连接、委派深度与并发上限——**这些不用改，本版按同名变量读**。

四项形态有变化，升级时要处理：

| 变量 | 变化 | 你要做什么 |
|---|---|---|
| `REDIS_PASSWORD` | 存量收明文，本版收密文 | **口令要先加密**，经解密接缝填充 |
| `RUNTIME_DB_*` 八项 | 两处消费：**Task 快照的数据库档**按 `runtime_db_*` 逐项取值（形态与存量相同）；存量兼容外观那条链按 backend 拼成一个连接串 | **不用改**——`RUNTIME_DB_ENABLED=true` 直接把 Task 快照切到数据库档，与存量同一行为；连接分项两处各按各自形态读 |
| `VERSATILE_ADAPTER_URL` | 存量给固定地址，本版给模板 | **不用改**——无占位时模板渲染恒等 |
| `VERSATILE_ADAPTER_TIMEOUT` | 存量默认 57，本版默认 600 | 你显式配了就按你的；没配则用本版默认 |

### 2.2 归宿主，本版不读（17 项）

这些配置**不是本版丢了功能，是它们本来就不属于 runtime**：

| 变量 | 归谁 | 依据 |
|---|---|---|
| `FASTAPI_HOST` / `PORT` / `DEBUG` / `WORKERS` | 你的 ASGI 服务器 | 权威 L1「HTTP server 的端口、TLS、反向代理、鉴权入口和网络策略由宿主应用或部署环境负责」 |
| `LOG_*` 五项、`JIUWEN_LOG_*` 四项 | 你的日志设施 | 本版用标准库 logging 且**不配置 root logger**——配置权是宿主的 |
| `HEARTBEAT_INTERVAL_SECONDS` / `TIMEOUT_SECONDS` | 你的接入层 | 宿主义务 H-SERVE-6 是 **MUST**：「应用层保活帧由宿主接入层产出——runtime **不产** heartbeat 帧。心跳属连接层关注点，谁持有连接谁负责保活」 |
| `RATE_LIMIT_*` / `GLOBAL_RATE_LIMIT_*` 四项 | 你的应用 | 宿主义务 H-SERVE-1 是 **MUST**：「限流由宿主应用治理，runtime 不提供限流」 |

**兼容入口读到这些不会告警**——它们本来就不该 runtime 管，为它们告警会把真问题淹掉。

**心跳与限流要你自己补**：前者在你的接入层产保活帧，后者在你的应用或网关做。存量把这两件放在 `a2a_service` 里，本版按职责划分把它们还给宿主。

### 2.3 归宿主 Agent（8 项）

`DPA_AGENT_ID`、`SUB_AGENT_TIMEOUT_SECONDS`、`MAX_PARALLEL_WORKFLOWS_PER_AGENT`、`WORKFLOW_TIMEOUT_SECONDS`、`AES_MASTER_KEY`、`ACTIVE_SCENARIO`、`SANDBOX_URL`、`SKILL_TARGET_PATH`。

**原样保留**，它们随你的 Agent 走。

### 2.4 本版不承接，建议从 `.env` 删掉（5 项）

`BOOTSTRAP_COORDINATION_ENABLED`、`BOOTSTRAP_LOCK_NAME`、`BOOTSTRAP_LOCK_TTL_SEC`、`BOOTSTRAP_WAIT_TIMEOUT_SEC`、`BOOTSTRAP_POLL_INTERVAL_SEC`。

**设置了会打可见告警。** 不承接的理由是四条实测事实：

- **临界区是空的**——存量的 `_run_global_bootstrap_once()` 函数体只有一行日志「LEADER 全局 bootstrap 无额外任务」
- **锁在真初始化之前就释放**——释放锁与 `await initialize()` 之间隔着几十行，所有副本仍并发跑初始化
- **出厂关闭但代码默认开**——`.env.example` 里是 `False`，而 `config.py` 的默认值是 `True`；不放 `.env` 直接跑，它是开的
- **开着会多一条失败路径**——等待超时 300 秒，follower 等不到就抛异常让启动失败，而其健康端点恒返回 healthy，探针看不出来

**承接的是缺陷不是能力。** 搬过来换不到任何行为，却要一起搬进脑裂窗口与一条启动失败路径。

若你确实需要跨副本的一次性初始化，用**部署编排层**的手段——容器编排的一次性任务或初始化容器，而不是在 runtime 里做。

---

## 3. 两份配置文件

| 文件 | 本版怎么读 |
|---|---|
| `channels.yaml` | `agent_runtime/bootstrap/legacy_compat/channels_config.py` |
| `orchestrator/config/route_config.yaml` | `agent_runtime/bootstrap/legacy_compat/route_config_loader.py` |

**放在原来的位置即可。**

一处限制：存量的 `routes` 是列表，而本版当前**只支持一个自定义 REST 路径模板**。配了多条时取 `default_route_key` 指名的那条（没指名则取第一条），**并打告警**——不会静默丢弃。

---

## 4. 调用方要不要改

**不用。** 本版对外形态与存量逐字节兼容，兼容面逐项登记在 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/COMPAT-SURFACE-INVENTORY.md`，八个维度各有判据：

| 维度 | 覆盖什么 |
|---|---|
| 入口面 | 端点路径与方法 |
| 事件类型面 | 北向 SSE 的事件名集合 |
| 响应信封面 | 字段集与键序 |
| HTTP 错误面 | 状态码与错误体形态 |
| 南向出向面 | 远端看我方的报文 |
| 共享存储键面 | 键名与值格式 |
| 远端通信对外行为 | 委派与投影 |
| **部署契约面** | **本文讲的这一维** |

差分判据的期望值**由存量代码在运行时算出**，不是手写字面量——存量改了什么，判据就转红。

---

## 5. 升级后确认清单

```bash
# ① 服务起来了
curl -sf http://<你的地址>/health

# ② 启动日志里的告警都看过了
#    「不承接」那几条 → 从 .env 删掉
#    没有别的告警 → 其余配置都有去向

# ③ 调用方的关键路径跑一遍
#    对外形态逐字节兼容，若有差异请对照兼容面清单第八维之外的七维
```

**要额外补的两件**：心跳（你的接入层）与限流（你的应用或网关）。

---

## 6. 相关文档

- EDPAgent 在本版上的部署（存量方式与 SDK 方式）`doc/deploy-edpagent.md`
- 环境变量样例 `deploy/.env.example`（第三部分是升级对照表）
- 逐项去向表 `internal/operate/ENV-MAPPING-FROM-A2A-SERVICE.md`
- 兼容面清单 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/COMPAT-SURFACE-INVENTORY.md`
- 宿主义务契约 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-host-obligations.md`
- 配置参考 `doc/configuration.md`、集成指南 `doc/integration-guide.md`
