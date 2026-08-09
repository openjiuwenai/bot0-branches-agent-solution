---
level: L1-HLD
module: agents/pev
TAG:
  - physical-view
  - deployment
  - architecture-fact
status: active
updated: 2026-08-06
dependency:
  - overview.md
  - process.md
  - development.md
---

# PEV L1 架构物理视图

## 1. 物理视图定位

物理视图描述 `agents/pev` 的部署形态、进程/网络边界、trace sink 消费拓扑和持久化边界。

**核心事实：PEV 没有独立进程、网络端点或持久化边界。** 它是一个 in-process agent 库（Maven jar），物理边界由宿主进程决定。

## 2. 部署形态

PEV 本身是 jar，无独立 runtime；它以三种物理形态落地，但都不改变 L1 逻辑边界：

| 形态 | 物理位置 | 控制流归属 | 说明 |
|---|---|---|---|
| in-process 直连 | 宿主应用进程 | PEV invoke 同步跑在调用线程 | 测试或嵌入式使用：直接 `new PEVAgent(...).invoke(...)`。 |
| runtime 包装 | runtime 进程（`agent-runtime-java`） | runtime handler 转发到 PEV invoke | PEV 被 runtime 注册为 agent，runtime 把 A2A Task/SSE 映射到 invoke；PEV 不感知 HTTP/A2A。 |
| 同级模式继承 | 兄弟 module（如 EDPA） | 子类 agent 的 invoke | extends PEVAgent / 复用 kernel，物理边界随兄弟 module。 |

无论哪种形态，PEV 闭环都在**宿主进程的一个线程**内同步跑完（`Runnable::run` 同步 verifier）。PEV 不 spawn 自己的后台进程或线程跑闭环。

## 3. 进程与网络边界

| 边界项 | PEV | 说明 |
|---|---|---|
| 独立进程 | 无 | PEV 是 jar，不是进程。 |
| 监听端口 | 无 | PEV 不开任何端口；HTTP/A2A 由 runtime 承接。 |
| 网络调用 | 不主动 | PEV 闭环保留 LLM/工具调用给注入的 Executor；PEV 自身不发起网络调用。 |
| 服务发现 | 无 | 不注册自身；被发现由 runtime 的 Agent Card 承接。 |

## 4. Trace sink 消费拓扑

`PevTraceSink` 是 PEV 唯一的可观测出口。它是函数式接口，默认 `noop()`，由宿主经构造器注入（显式 opt-in）。sink 的物理消费拓扑由宿主决定：

```text
PEVAgent ──emitTrace──▶ PevTraceSink（实例字段）
                              │
                              ├─ noop()                 默认，丢弃
                              ├─ host logger            宿主进程日志
                              ├─ OTel exporter          宿主进程 → OTel collector（宿主配置）
                              ├─ Micrometer meter       宿主进程 → metrics 后端
                              └─ test collector         测试采集（内联 lambda sink）
```

- **实例 scope**：sink 是 PEVAgent 字段，非进程级 static——每个 PEVAgent 实例有自己的 sink，规避并发实例污染。
- **FutureTask 桥隔离**：sink 抛错不击穿 invoke；sink 故障是宿主侧可观测事件，不影响 PEV 控制流。
- **单次终态 emit**：trace 在 invoke 返回时一次性投递，不在循环中途反应。

## 5. 持久化边界

| 状态 | 持久化 | 归属 |
|---|---|---|
| `completed`/`terminal`/`retryCount` | 不持久化（invoke 局部） | PEV，随 invoke 结束而消亡 |
| `PevTrace` | 不持久化（终态副产品） | PEV 投递给 sink；是否落盘由 sink 决定 |
| `PevConfig` | 由构造/配置时持有 | PEV 实例 |
| 服务端 Task 状态 | 不归属 PEV | runtime（PEV 被 runtime 包装时） |

PEV 跨 invoke 无状态——不保持闭环运行态，不替代 runtime checkpoint，不做平台审计写入。

## 6. 凭据与敏感数据边界

`agents/pev` **不持有、不处理凭据或敏感数据**：

- LLM 凭据、业务凭据、敏感正文由**宿主注入的 Executor/Verifier** 在其内部管理；PEV 只在 SPI 间传递 NL 文本与 `NodeResult` 值，不接触凭据材料。
- PEV 不做租户认证、不做凭据刷新、不持久化敏感正文——这些在 `agent-runtime` / `agent-client` / ingress 层。
- `PevTrace` 投影的是相位 + 终态原因，不含凭据/prompt 正文/工具结果正文；但 `NodeResult.Success.value` 可能携带业务数据——宿主 sink 实现须自行脱敏（PEV 不强制脱敏策略）。

> 诚实标注：凭据/敏感数据治理是 runtime/agent-client 层职责，**PEV 层 N/A**；PEV 只保证不在 trace/sink 中主动新增凭据字段。

## 7. 国产化硬件适配（鲲鹏 / 昇腾）

- `agents/pev` 是纯 JVM agent 库，本身不做模型推理，**不直接依赖昇腾算子或加速库**；模型推理与硬件加速发生在 runtime 及其后端（compute plane），PEV 不承载。
- 物理适配要求集中在**运行环境**：PEV 必须能在鲲鹏（aarch64）架构的 JVM 上正常构建与运行；依赖项（`agent-core-java`、junit、assertj）须具备 aarch64 兼容实现，不得引入仅 x86 的原生依赖。
- 因此 PEV 应只依赖纯 Java 实现，避免绑定平台相关 native 库，以保持在国产化硬件与通用硬件上的一致可移植性。

## 8. 部署边界不变量汇总

- PEV 无独立进程、无入站端口、无 webhook、无 runtime 直连——它是 in-process jar。
- 闭环运行态（completed/terminal/retryCount）是 invoke 局部，不跨调用持久化。
- `PevTraceSink` 实例 scope（PEVAgent 字段），非进程级 static。
- PEV 不持有凭据/敏感数据（§6）；sink 投影不含凭据字段。
- PEV 在鲲鹏 aarch64 JVM 上可移植；硬件加速不下沉到 PEV。

## 9. 与其他视图的衔接

- 状态归属与领域对象：`logical.md`。
- 运行时相位流、线程模型、资源生命周期：`process.md`。
- 代码分层、依赖红线、构建基线：`development.md`。
- 技术场景：`scenarios.md` TS-01 ～ TS-09。
- 接入契约、sink 消费细节、对 runtime 要求：L2 `Feat-Func-023-pev-selfheal-loop.md`。
