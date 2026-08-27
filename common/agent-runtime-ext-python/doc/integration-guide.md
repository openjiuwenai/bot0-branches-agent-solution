# 集成指南

面向**接入方**：把一个 Agent 装进本 runtime、跑起来、验证它工作。

本文只做归集，每一条的事实源在对应的 L2 详设（`internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/`），与详设冲突时以详设为准。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| Python | `>= 3.11` |
| 宿主应用 | 一个 ASGI 应用（参考实现用 FastAPI + uvicorn） |
| 你的 Agent | 任何能被包成 `AgentHandler` 的东西——框架实例、远端服务、或自己写的执行逻辑 |

本 runtime 是**嵌入宿主的 SDK**，不是一个可独立运行的服务。它不会自己找配置文件、不会自己监听端口、不会反向调用宿主的入口。

## 2. 安装

```bash
pip install -e .
```

核心依赖极薄：`a2a-sdk`、`fastapi`、`uvicorn`、`sse-starlette`、`httpx`。适配件按需装——缺哪个包的错误信息会指明包名，而不是抛原始导入错误。

## 3. 实现执行契约

`AgentHandler` 是一个 `Protocol`（结构化子类型），**不要求继承**，方法面对得上即可。显式继承它可获得 mypy 的结构符合性检查。

```python
from collections.abc import AsyncIterator

from agent_runtime.ports.handler import AgentHandler
from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


class MyHandler(AgentHandler):
    async def query(self, request: ServeRequest) -> QueryResponse:
        answer = await my_agent.run(request.query)
        return QueryResponse(output=answer)

    def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        async def _gen() -> AsyncIterator[QueryChunk]:
            async for piece in my_agent.stream(request.query):
                yield QueryChunk.of_chunk(piece)
        return _gen()
```

`start` / `stop` / `clear_session` / `is_healthy` 有默认实现，不需要的可以不写。

### 3.1 失败必须让异常传播

发一个错误块之后**还必须让异常传播出去**：

```python
        except SomeError as exc:
            yield QueryChunk.of_error(str(exc), code="MY_ERROR", kind="transport")
            raise                      # ← 不能省
```

只发块不传播会让失败静默丢失——入口层看不到异常就不会补失败终态帧，Task 停在执行中而不是落 FAILED。

### 3.2 不要在处理器里碰协议类型

处理器看不到、也不该看到 A2A 或 HTTP 的任何类型。协议解析与响应投影由入口适配件负责，处理器只处理 `ServeRequest` 与 `QueryChunk`。这条由架构门禁静态守住。

## 4. 装配

### 4.1 只起标准协议入口

```python
from agent_runtime.bootstrap.a2a_app import create_a2a_app

app = create_a2a_app(
    MyHandler(),
    name="my-agent",
    description="……",
    version="1.0.0",
    url="http://127.0.0.1:8080/a2a/",
    mount_path="/a2a",
)
```

得到的是一个 ASGI 应用，直接交给 uvicorn。

### 4.2 同时起自定义 REST 入口

两条入口**必须共用同一个编排器**——把前者产出的注入后者：

```python
from agent_runtime.adapters.inbound.rest.router import build_rest_router

app.include_router(
    build_rest_router(
        MyRestChannel(),
        app.state.orchestrator,        # ← 共用，不要另建
        task_store=app.state.task_store,
    )
)
```

各建各的编排器会让两条入口看到不同的 Task 状态。

### 4.3 启动钩子

ASGI 框架的 lifespan 与 `on_event("startup")` **互斥**。组合根一旦把 runtime 的生命周期挂成 lifespan，宿主原先用 `on_event` 注册的启动逻辑就再也不会执行，**且不报任何错**。

故宿主的启动逻辑要作为钩子传入，由生命周期在处理器启动**之前**按序执行：

```python
app = create_a2a_app(MyHandler(), name="my-agent", init_hooks=[my_startup])
```

## 5. 配置

见《配置参考》`doc/configuration.md`。要点：

- 全部配置落在 `openjiuwen.service.*` 下，没有第二个命名空间
- **SDK 自己不去找配置文件**——宿主用 `ConfigLoader` 加载后传进来
- 环境变量始终参与，格式 `前缀__A__B=值`，层级分隔是**双下划线**
- 可照抄的环境变量样例在 `deploy/.env.example`（第二部分），每一项都经判据核对能绑到配置字段

## 6. 验证非流式请求

```bash
curl -s -X POST http://127.0.0.1:8080/a2a \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{…}}'
```

Agent Card 发现有**三条独立路径**，都要能通：

```bash
curl -s http://127.0.0.1:8080/.well-known/agent-card.json
curl -s http://127.0.0.1:8080/a2a/.well-known/agent-card.json
curl -s http://127.0.0.1:8080/.well-known/agent.json
```

## 7. 验证流式请求

```bash
curl -s -N -X POST http://127.0.0.1:8080/a2a \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"2","method":"SendStreamingMessage","params":{…}}'
```

每帧带 `event: jsonrpc` 事件名。**单元测试覆盖不到 wire 契约层面的缺陷**——本仓的部署级端到端脚本（`deploy-e2e/`）用真容器、真 socket、真协议库验这一层，接入方也建议照此形态自建一条。

## 8. 验证失败路径

比正常路径更值得验，因为它的缺陷不会自己暴露：

- 让处理器抛异常，确认对外信封的成功位为假、错误文案到达调用方
- 确认 `output` 位为空——传输层故障不得伪装成 Agent 的业务输出
- 确认 Task 落失败终态，不是停在执行中

参考 `deploy-e2e/run-versatile-down.sh`：把远端地址指向一个无人监听的端口，连接被拒是确定性的即时失败，不需要等超时。

## 9. 排障

| 现象 | 多半是什么 |
|---|---|
| 配置文件写了一堆，一个字都不生效，也不报错 | 宿主没把加载结果传进装配函数——SDK 自己不读文件 |
| 宿主的启动逻辑不执行，运行期取到未初始化的资源 | 用了 `on_event("startup")` 而不是 `init_hooks`（见 §4.3） |
| 两条入口看到的 Task 状态不一致 | 各建了各的编排器（见 §4.2） |
| 单元测试全绿但线上帧序不对 | 单测跑在替身上，替身的行为是自己写的；补一条真 socket 的往返验证 |
| Task 停在执行中不落失败态 | 处理器发了错误块但没让异常传播（见 §3.1） |
| 装了多个适配器包，选到的不是想要的那个 | 显式配 `openjiuwen.service.adapter.name`；自动选取虽有确定规则，但「装了什么」会随打包变化 |

## 10. 相关文档

- 《入口与数据契约》`doc/entrypoints-and-contracts.md`
- 《配置参考》`doc/configuration.md`
- 参考宿主 `deploy/host_app.py`、容器入口 `deploy/Dockerfile`
- 部署级端到端脚本 `deploy-e2e/`
