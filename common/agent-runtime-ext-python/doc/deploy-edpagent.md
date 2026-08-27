# EDPAgent 在本版上的部署

面向**部署方与 EDPAgent 维护方**：把 EDPAgent（`common/agents/edp-agent-python`）跑在本 runtime 上，有两种接入方式；本文讲各自怎么装、怎么起、怎么验、怎么容器化。

本文只做归集，接入形态的事实源在 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-legacy-internal-api-compat.md` §2.1 D 族与 `Feat-Func-002b` §4.4.3，冲突时以详设为准。

---

## 1. 两种方式，选哪种

| | 存量方式 | SDK 方式 |
|---|---|---|
| 适用 | 手里有一套按 `applications/a2a_service` 部署的 EDPAgent，要换 runtime、其余不动 | 新部署，或 EDPAgent 已提供自己的处理器 |
| 宿主 | 兼容入口 `python -m agent_runtime.bootstrap.legacy_compat` | 你自己的进程（参考 `deploy/host_app.py`） |
| EDPAgent 怎么接 | 按存量导入名 `agents.EDPAgent` 自动装载：启动调一次 `initialize()`，每次请求调 `agent_stream(...)`，实参与存量执行器相同 | EDPAgent 实现 `AgentHandler`（对位 Java 的 `EdpaExtHandler extends JiuwenCoreAgentExtHandler`；Python 可继承或组合 `agent_runtime/adapters/outbound/agentcore/handler.py` 的 `AgentCoreHandler`），由 `create_a2a_app` / `create_rest_app` 装配 |
| 配置 | 原来的 `.env`（去向见 `doc/upgrade-from-a2a-service.md`） | `doc/configuration.md` 的配置文件 + `OPENJIUWEN__SERVICE__*` |
| 定位 | 绞杀者迁移的过渡形态，EDPAgent 给出处理器后下线 | 目标形态 |

两种方式的对外形态逐字相同（同一份参考宿主装配，差的只是背后谁在执行）。

---

## 2. 存量方式

### 2.1 安装

```bash
# 本版 runtime（含参考宿主 deploy/ 与兼容入口）
pip install -e <runtime 仓根>
# EDPAgent 依赖（openjiuwen、loguru、httpx 等）按 EDPAgent 自己的 pyproject 装
pip install -e <agent-solution>/common/agents/edp-agent-python
```

### 2.2 代码落位

与存量完全相同：EDPAgent 放在 `applications/a2a_service/agents/EDPAgent/`，`agents/` 是一个包（有 `__init__.py`）。EDPAgent 自己 `import common.logger` / `common.crypto`，这两个模块也在 `applications/a2a_service/common/` 下，所以 **`applications/a2a_service` 目录要在 `PYTHONPATH` 上**；参考宿主在 `deploy/` 下，也要在：

```bash
export PYTHONPATH=<a2a_service 目录>:<runtime 仓根>:<runtime 仓根>/deploy
```

导入名可用 `RUNTIME_LEGACY_AGENT=<包.模块>` 改；导入失败时报错会指出落位与这个变量。

### 2.3 配置

`.env` 原样保留。EDPAgent 自己读的那 81 项（`PLANNING_AGENT_MODEL_*`、`DPA_*`、`OTEL_*`、`MCP_*`…）与 runtime 换不换无关；`a2a_service` 读的 49 项去向见升级说明。样例与逐项标注在 `deploy/.env.example` 第三部分。

三项必须有值，否则起不来：

| 变量 | 为什么 |
|---|---|
| `PLANNING_AGENT_MODEL_API_KEY` / `_BASE_URL` / `_NAME` | EDPAgent 启动期就校验模型配置 |
| `REDIS_HOST` / `REDIS_PORT` | EDPAgent 的 checkpointer 与本版的共享存储都用它 |
| `DPA_AGENT_ID` | 宿主 Agent 的服务身份（未设时沿用参考宿主默认身份 `mobile_bank_agent`） |

不要设 `RUNTIME_BACKEND`——它有值时兼容入口改用参考宿主的内建后端（替身或透传工作流），**不装载 EDPAgent**；那是留给部署级验证的口子。

### 2.4 启动

```bash
# 存量：python main.py
python -m agent_runtime.bootstrap.legacy_compat          # host/port/workers 仍从 FASTAPI_* 读
```

启动日志里看两行：`按存量导入名装载宿主 Agent：agent_id=<DPA_AGENT_ID>`、`宿主 Agent 就绪：agent_id=…`。没有第一行说明 `RUNTIME_BACKEND` 被设了；没有第二行说明 `initialize()` 没跑完（多半是模型或 Redis 配置）。

### 2.5 验证

```bash
curl -sf http://127.0.0.1:8090/health
# 同步
curl -s -H 'Content-Type: application/json' \
  http://127.0.0.1:8090/v1/proj/agents/<DPA_AGENT_ID>/conversations/c1 \
  -d '{"input":{"query":"你好"},"stream":false}'
# 流式
curl -s -N -H 'Content-Type: application/json' \
  http://127.0.0.1:8090/v1/proj/agents/<DPA_AGENT_ID>/conversations/c2 \
  -d '{"input":{"query":"你好"},"stream":true}'
```

同步返回 `{"success": true, "answer": "…"}`（执行失败时 `success: false` 并带 `error`），流式返回 `data:` 帧。仓内同一件事的自动化版本是 `deploy-e2e/run-legacy-edpagent.sh`：`make e2e-legacy-edpagent`（需 `LLM_BASE` / `LLM_API_KEY` / `LLM_MODEL`、已导出的存量副本与 Redis；`E2E_BACKEND=local` 在宿主机直跑、`docker` 在容器里跑、默认有容器运行时就用容器）。

### 2.6 容器化

可运行的样本是 `deploy-e2e/Dockerfile.legacy-edpagent`（部署级验证 `run-legacy-edpagent.sh` 用它起受测端）。要点：

```dockerfile
# 依赖：runtime 的 requirements + EDPAgent 顶层 import 的三方包
RUN uv pip install -r /tmp/requirements.txt && \
    uv pip install "openjiuwen[observability]==0.1.16" loguru python-dotenv pyyaml pycryptodome
COPY agent_runtime /app/agent_runtime
COPY deploy        /app/deploy
COPY legacy        /app/legacy          # 存量落位：legacy/agents/EDPAgent、legacy/common
ENV PYTHONPATH=/app/legacy:/app:/app/deploy
CMD ["python", "-m", "agent_runtime.bootstrap.legacy_compat"]
```

`.env` 由编排层注入（`docker run --env-file .env`，或平台的环境变量），镜像里不放密钥。宿主机直跑与容器两种形态都支持：EDPAgent 未配置沙箱时 `execute_cmd` 直接在进程所在机器上跑 shell——信任 Agent 的环境（测试环境）直接在宿主机跑即可，生产或不信任时以容器为边界。心跳与限流归宿主接入层／网关（宿主义务 H-SERVE-6、H-SERVE-1），存量放在 `a2a_service` 里的这两件本版不提供。

### 2.7 已知边界

- **子 Agent 批次回灌的续轮**：存量以 `cascade_result` 承载，本版的委派回灌走远端协作面，存量方式下该实参恒为空。
- **会话态**：存量方式下 EDPAgent 自管 session／checkpoint／取消后复位（在它的 `agent_stream` 里）；runtime 的 Task 状态照旧外置。SDK 方式把这些交给处理器基类。
- **多 worker**：兼容入口拒绝 `FASTAPI_WORKERS>1`（与升级说明一致）。

---

## 3. SDK 方式

1. 实现 `AgentHandler`（`agent_runtime/ports/handler.py`）：`start` 里做一次性初始化，`stream_query` 产 `QueryChunk` 流，`query` 走同一执行路径。EDPAgent 基于 openjiuwen agent-core，可继承或组合 `AgentCoreHandler`，只在其上加自己的初始化与守卫（Java 侧 `EdpaExtHandler` 正是这个形态）。
2. 装配：`create_a2a_app(handler, ...)`，需要自定义 REST 入口时再 `include_router(build_rest_router(channel, orchestrator, ...))`，参考 `deploy/host_app.py::create_app`。
3. 配置与启动见 `doc/integration-guide.md`、`doc/configuration.md`，环境变量样例见 `deploy/.env.example` 第一、二部分。

存量方式的每一条判据（`agent_runtime/tests/test_legacy_host_agent.py`）与端到端验证对 SDK 方式同样适用——切换后跑同一批。

---

## 4. 相关文档

- 《从 a2a_service 升级到本版》`doc/upgrade-from-a2a-service.md`
- 《集成指南》`doc/integration-guide.md`、《配置参考》`doc/configuration.md`
- 环境变量样例 `deploy/.env.example`
- 兼容详设 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-legacy-internal-api-compat.md` §2.1 D 族
