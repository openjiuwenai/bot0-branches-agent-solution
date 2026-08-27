# agent-runtime · Python 交付分支

嵌入宿主的 Agent 托管运行时 SDK。本分支是**完整交付件**：详细设计、代码、单元测试、容器级端到端验证、打包与运行脚本。

## 三行跑起来

```bash
make setup    # 建虚拟环境、装依赖、可编辑安装本包
make check    # 单元测试 + 依赖方向门禁 + 静态检查
make e2e      # 五条容器级部署端到端验证（需 docker）
```

`make check` 与 `make e2e` 都过，才算这份交付可用。**单元测试全绿不构成交付证据**——wire 契约缺陷只有容器级往返能抓到，本项目已有多次实证。

> **Windows + PyCharm 开发**：门禁与检查跨平台同一条命令——`python tools/tasks.py check`，`make check` 只是类 Unix 侧对它的转调。逐步操作见 **[`docs/ops/SETUP-DEV.md`](docs/ops/SETUP-DEV.md)**。
>
> 想让 AI 编码助手代劳整个搭建过程：把 **[`docs/ops/SETUP-AGENT-TASK.md`](docs/ops/SETUP-AGENT-TASK.md)** 整份交给它。该文自包含，只会问你一个问题（PyCharm 安装路径）。
>
> 环境已建好、只想拉最新代码跑一遍门禁：把 **[`docs/ops/E2E-AGENT-TASK.md`](docs/ops/E2E-AGENT-TASK.md)** 交给它。**不需要容器运行时。**

## 目录

| 目录 | 内容 |
|---|---|
| `agent_runtime/` | SDK 本体。按洋葱架构分层：领域、契约、编排、装配、适配 |
| `doc/` | **面向使用方的三份**：配置参考、入口与数据契约、集成指南 |
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/` | 详细设计二十份：13 份特性详设（`Feat-Func-*b`）+ 总体设计、数据架构视图等七份根设计与横切件 |
| `docs/ops/` | 部署、测试与验收台账。按读者分工，索引见该目录 `README.md` |
| `deploy/` | 参考宿主：`host_app.py` 把 SDK 装进 FastAPI，`Dockerfile` 给出容器入口 |
| `deploy-e2e/` | 容器级端到端验证：17 个服务变体 + 20 个运行脚本 + 镜像定义 |
| `tools/` | 依赖方向守门与其自身的测试。**见 `tools/README.md`** |
| `oracle_support/` | 黄金基线：我方对存量对外事实的记录，差分判据据它比对 |
| （存量副本） | **不留在本仓**。差分验证的真值由 `tools/legacy_oracle.sh` 按锚定提交临时导出到 `.legacy-oracle/`、用后即删——副本只要可写就一定会被改，届时「存量真值」就不再是存量 |
| `CONTEXT.md` | 统一语言表。术语判据从它读禁用词清单 |

## 设计文档怎么读

先读两份根设计，其余按需：

| 文档 | 作用 |
|---|---|
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` | **设计根基**。六条设计原则（不可违背）、领域模型、分层、公共端口、横切不变量、冲突处置规则 |
| `docs/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-data-architecture-view.md` | **设计根基**。进程内运行态与可外置状态的分界、键面、过期、写序、已知限制 |
| `Feat-Func-000b-python-runtime-framework.md` | 框架基座：扩展点发现与装配、生命周期编排、就绪态、在途流登记、配置 |
| `Feat-Func-001b-standardized-agent-service-entrypoint.md` | 标准 A2A 服务入口 |
| `Feat-Func-002b-heterogeneous-agent-framework-compatibility.md` | 异构框架兼容·总览：统一执行契约、结果流归一、取消传导 |
| `Feat-Func-002b-local-framework-adaptation.md` | 本地框架适配（同进程执行框架） |
| `Feat-Func-002b-versatile-remote-service-proxy.md` | 远端服务代理（非标准协议的远端 Agent） |
| `Feat-Func-004b-task-driven-remote-agent-communication.md` | 任务驱动的远端 Agent 通信 |
| `Feat-Func-003b-agent-task-state-cache.md` | 智能体任务状态缓存 |
| `Feat-Func-005b-agent-middleware-request-proxy.md` | 智能体中间件请求代理（技能中心） |
| `Feat-Func-008b-user-interaction-interrupt-and-response.md` | 用户交互中断与续接 |
| `Feat-Func-009b-runtime-response-client-side-tool-calling.md` | 运行时响应端侧工具调用 |
| `Feat-Func-017b-bus-event-subscription-consumption.md` | 总线事件订阅消费 |
| `Feat-Func-022b-custom-rest-api-agent-service-entrypoint.md` | 自定义 REST API 服务入口 |
| `Feat-Func-027b-standard-streaming-response-data-protocol.md` | 标准流式响应数据协议 |
| `L2-host-obligations.md` | **宿主义务契约**：集成本 SDK 的宿主与部署方各要做什么，附违反后果 |
| `L2-deployment-cutover.md` | 部署与切流 |

## 两条硬约束

**一 · 依赖方向是门禁，不是约定。** 它既是设计原则也是开发原则，因此固化在 `make check` 与 CI 里，任一违规即红：内层导入外层、内层导入第三方框架、新建的包未在层清单中显式落层。误报的处置是在 `tools/arch-guard-baseline.txt` 里逐条写明理由，**不是关掉门**。

**二 · 差分判据要先导出存量真值。** 存量副本**不留在本仓**——跑差分前先执行 `tools/legacy_oracle.sh fetch`，它按 `tools/authority-pins.toml` 的锚定提交导出到 `.legacy-oracle/`，`agent_runtime/tests/conftest.py` 自动挂上导入路径；未导出时那组判据**显式跳过，不静默通过**。用后执行 `tools/legacy_oracle.sh clean` 删除。

## 存量兼容怎么验

**对外与存量逐字节等价**是本版首要设计原则。它不由单一手段保证，而是分面覆盖——下表说明**哪一面由谁验、期望值从哪来**。

| 兼容面 | 验证物 | 期望值来源 |
|---|---|---|
| 自定义 REST 通道的事件投影与帧序 | `agent_runtime/tests/test_differential_vs_oracle.py`（17 项）、`test_differential_frame_sequence.py`（9 项） | **存量代码在运行时算出**，非手写字面量 |
| 标准协议入口的 wire 契约 | `deploy-e2e/run-a2a-northbound.sh`（四项断言） | 真容器 + 真 socket + 真协议库实测 |
| 远端服务代理的 wire | `deploy-e2e/run-versatile.sh`（六项断言） | 存量出站报文的实测形态 |
| 远端不可达时的失败终态 | `deploy-e2e/run-versatile-down.sh`（六项断言） | 连接被拒的真 socket 往返实测 |
| 端侧工具的 wire 契约 | `deploy-e2e/run-client-tool.sh` | 容器内两请求往返实测 |
| 远端工具往返 | `deploy-e2e/run-remote-tool.sh` | 容器内线级往返实测 |
| 共享的存储键面与值格式 | `agent_runtime/tests/test_shared_session_keys.py`（4 项） | 交叉核对存量的冻结事实清单 |

### 差分验证最值钱的地方

它的期望值**不是任何人写的**——把同一批输入同时喂给存量与新实现，断言输出逐字节一致（含字段顺序），期望值由存量代码运行时算出。

其余判据多数是手写字面量，那锁的是「我以为存量会输出什么」；差分验证锁的是「存量实际输出了什么」。判定用序列化后的字符串相等，因为**存量客户端正是按该序列化按位取值，字段顺序漂移即破兼容**。

### 它不覆盖什么

差分验证只覆盖自定义 REST 通道那一面。标准协议面、远端出站、键面、错误信封各由上表其余各行承担。**把差分验证当成「存量兼容已验」的全部，是这张表存在的理由。**

### 单元测试全绿不构成交付证据

本项目已有多次实证：wire 契约缺陷只在容器级往返暴露，而单元测试全绿。典型三例——终答被完成信号吞掉、首帧即中断时对外报错码错误、端侧工具投影丢空串。**`make check` 与 `make e2e` 都过，才算这份交付可用。**

## 当前实现状态

**这一节必须读。** 仓内有部分代码尚无装配点，测试绿灯只证明代码自洽，不证明它在生产路径上生效。

| 状态 | 范围 |
|---|---|
| 已接线且经容器验证 | 自定义 REST 入口、本地框架适配（三形态）、远端服务代理、端侧工具、远端工具往返、取消链路、生命周期启停与排水 |
| 已实现但生产零装配点 | 任务状态缓存的部分构件、中断协调器、远端批次协调器 |
| 详设已定、实现待补 | 对外协议入口的六条端点可达性与回调接收、远端出站的端口形态。逐条登记在各详设的「限制与待补」章，标注为「处置已定」 |

各详设的「限制与待补」章区分**刻意取舍**、**能力上限**与**真缺口**三类——只在真缺口上投入。
