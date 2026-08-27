# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long,no-self-use,add-staticmethod-or-classmethod-decorator


"""参考宿主装配：把本版 runtime 装成一个可替换存量服务的进程。

## 这个文件为什么存在

本版是**嵌入宿主的 SDK**，不是一个开箱可执行的服务——它导出装配工厂
（`create_rest_app` / `create_a2a_app`），由宿主决定装什么处理器、接什么存储、
挂什么路由。存量 `a2a_service` 则是一个完整应用。

于是「用本版替换存量」这句话缺一个东西：**宿主装配**。没有它，测试人员
拿到仓库也起不来服务。本文件补的就是这一段，把两个入口按存量的对外形态
拼成一个进程。

## 它不是产品件

参考装配的定位是**受测装置**：真实宿主会有自己的配置体系、鉴权、可观测接入，
那些不属 runtime 的职责，本文件也不代劳。它只保证一件事——
**对外可观察的面与存量一致**，从而黑盒用例可以两侧对照着跑。

## 两档后端

黑盒验证有两类目标，它们对后端的要求相反：

| 档 | 后端 | 验什么 | 为什么必须是这个后端 |
|---|---|---|---|
| 契约档 | 确定性替身 | 报文形态、事件类型、错误信封、状态码 | 真实智能体输出不确定，逐字节比对无从谈起 |
| 贯通档 | 真实 agent-core | 请求真的驱动了执行、答复真的回来了 | 替身证明不了链路通 |

由 `RUNTIME_BACKEND` 选择，默认契约档（无外部依赖、可重复执行）。
**两档跑的是同一套装配代码**，差别只在注入的处理器——否则契约档验过的东西
在贯通档上未必成立。

## 与存量对外面的差异

三处，均为设计既定，不是缺陷：

| 面 | 存量 | 本装配 | 依据 |
|---|---|---|---|
| `/health` | 应用自带 | **宿主提供**（本文件提供） | 健康检查属应用装配层，不属 runtime |
| 模拟工具路由 | 有 | **不提供** | 存量的开发期工具，非对外契约面 |
| 站点根卡片端点 | 无 | **提供两条** | 协议要求卡片发现在站点根；存量无调用方指向它们，新增不改变既有路径行为 |
| 无尾斜杠协议路径 | 回 307 重定向 | **直接承载** | 权威要求两条路径都承载；跟随重定向的客户端照常成功、少一次往返 |
"""

from __future__ import annotations

import asyncio
import os
from typing import Any, AsyncIterator

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from agent_runtime.adapters.inbound.rest.mobile_bank import MobileBankChannel
from agent_runtime.adapters.inbound.rest.router import build_rest_router
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.cache_wiring import build_a2a_stores_with_init

# `ConfigSource` / `SourceKind` **必须是模块级导入，不能放函数里**：
# 下面的装配处用条件表达式引用它们，而条件为假时不求值——
# 未配置配置文件时看起来一切正常，一旦部署方配上
# `OPENJIUWEN__SERVICE__CONFIG_FILE` 才在启动期抛 NameError。
from agent_runtime.bootstrap.config.loader import ConfigLoader, ConfigSource, SourceKind
from agent_runtime.bootstrap.config.runtime_config import RuntimeConfig
from agent_runtime.bootstrap.extension_wiring import resolve_credential_decryptor
from agent_runtime.domain.result import QueryChunk, QueryResponse
from agent_runtime.ports.secret import SecretValue

#: 服务身份。写进南向报文的会话上下文与 A2A 卡片，两处都是对外可观察的。
AGENT_ID = os.environ.get("RUNTIME_AGENT_ID", "mobile_bank_agent")

#: 监听端口。与存量默认值一致，替换时不需要改调用方配置。
PORT = int(os.environ.get("RUNTIME_PORT", "8090"))

#: 对外基址，用于 A2A 卡片里声明自身端点。远端调用方按卡片回连，写错即回连不上。
SELF_URL = os.environ.get("RUNTIME_SELF_URL", f"http://127.0.0.1:{PORT}/a2a/")

#: 后端选择：`fixture`（确定性替身，默认）或 `agentcore`（真实执行后端）。
BACKEND = os.environ.get("RUNTIME_BACKEND", "fixture")

#: 替身的帧间延迟（秒），默认零。
#:
#: **关停排水那条用例离了它无法执行**：替身瞬间产完三帧，流在信号发出前就结束了，
#: 于是「排水生效」与「流本来就没了」两种情形读数相同——用例恒过，什么也没验到。
#: 拉开帧间隔，流才在关停时刻真的处于在途状态。
#:
#: 只影响产出节奏，不影响帧内容——其余用例的预期值不因它变化。
FIXTURE_DELAY_S = float(os.environ.get("RUNTIME_FIXTURE_DELAY_S", "0"))


# ── 契约档：确定性替身 ──────────────────────────────────────────

#: 固定事件序列。**用例的预期值以此为准**——序列一改，预期全部作废。
#: 形态取自存量真实帧序（单个子智能体从开始到完成）。
FIXTURE_EVENTS: list[tuple[str, str, str, dict]] = [
    ("thought", "先看一下账户", "", {}),
    ("tool_start", "调用查询", "query_balance", {}),
    ("final_answer_chunk", "余额为 100.00 元", "", {}),
]

#: 替身的终答文本。聚合响应的 answer 位取它。
FIXTURE_ANSWER = "余额为 100.00 元"


class _FixtureHandler:
    """产出固定结果序列的处理器。

    **不依赖任何外部服务**——无模型、无网络、无存储。这让契约档的用例
    可重复执行：同一请求任意次数得到同一响应，读数差异只可能来自被测代码。
    """

    agent_id = AGENT_ID
    priority = 0

    def is_healthy(self) -> bool:
        return True

    async def stream_query(self, request: Any) -> AsyncIterator[QueryChunk]:
        for event_type, content, plugin, data in FIXTURE_EVENTS:
            if FIXTURE_DELAY_S:
                await asyncio.sleep(FIXTURE_DELAY_S)
            yield QueryChunk.of_event(event_type, content=content, data=data, plugin=plugin)
        yield QueryChunk.of_final_answer(FIXTURE_ANSWER)

    async def query(self, request: Any) -> QueryResponse:
        """非流式聚合：drain 自身流取终答。

        **返回类型须是 `QueryResponse`**——端口 `agent_runtime/ports/handler.py` 就是这么
        声明的，三个真实处理器实现也都这么产。此前本替身返回的是最后一个结果块，
        与端口声明不符：装配期的契约校验只比成员面与签名，**不比返回类型**，
        故这类偏差在装配期看不见，要到消费方按 `{result, conversation_id}` 取值时才现形。
        """
        answer = ""
        async for chunk in self.stream_query(request):
            if chunk.is_answer:
                answer = chunk.content
            elif chunk.is_completion and chunk.content and not answer:
                answer = chunk.content  # 完成正文只作回落（Feat-Func-002b §4.4）
        return QueryResponse(result=answer, conversation_id=getattr(request, "conversation_id", ""))

    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    async def clear_session(self, conversation_id: str) -> None: ...


# ── 贯通档：真实执行后端 ────────────────────────────────────────


#: 贯通档的工作流标识。
WORKFLOW_ID = os.environ.get("RUNTIME_WORKFLOW_ID", "blackbox_wf")


def _register_workflow() -> None:
    """注册一条透传工作流。

    **必须在事件循环内调用**：工作流编译过程使用异步原语，模块导入期调用会失败。
    故它由初始化钩子在启动阶段触发，不在模块顶层执行。

    形态取自本仓已跑通的端到端入口（`deploy-e2e/e2e_server.py`），
    不是照着接口文档推的——这段构造此前凭印象写成 `add_node`／`add_edge`／`compile`
    三个并不存在的方法，服务在导入阶段即崩溃，而契约档不走这条分支、全程没暴露。
    """
    from openjiuwen.core.runner import Runner
    from openjiuwen.core.workflow import (
        End,
        Start,
        Workflow,
        WorkflowCard,
        WorkflowComponent,
    )

    class _StartNode(Start):
        def __init__(self, node_id: str) -> None:
            super().__init__()

        async def invoke(self, inputs, session, context):  # noqa: ANN001
            return inputs

    class _PassNode(WorkflowComponent):
        def __init__(self, node_id: str) -> None:
            super().__init__()
            self.node_id = node_id

        async def invoke(self, inputs, session, context):  # noqa: ANN001
            return inputs

    class _EndNode(End):
        def __init__(self, node_id: str) -> None:
            super().__init__()

        async def invoke(self, inputs, session, context):  # noqa: ANN001
            return inputs

    card = WorkflowCard(id=WORKFLOW_ID, name=WORKFLOW_ID, version="1")
    flow = Workflow(card=card)
    flow.set_start_comp("start", _StartNode("start"), inputs_schema={"query": "${query}"})
    flow.add_workflow_comp(
        "node_a", _PassNode("node_a"), inputs_schema={"output": "${start.query}"}
    )
    flow.set_end_comp("end", _EndNode("end"), inputs_schema={"result": "${node_a.output}"})
    flow.add_connection("start", "node_a")
    flow.add_connection("node_a", "end")
    Runner.resource_mgr.add_workflow(flow.card, lambda: flow)


async def _init_workflow() -> None:
    """初始化钩子：在处理器启动之前注册工作流。

    **不能用框架的启动事件装饰器**：组合根已把生命周期挂成标准的应用生命周期钩子，
    二者互斥——用事件装饰器注册会静默不执行，运行期取到空的工作流实例才暴露。
    组合根为此提供初始化钩子参数。
    """
    _register_workflow()


def _build_agentcore_handler() -> Any:
    """接真实执行后端。

    **惰性导入**：契约档不该因为环境里没装执行后端而起不来。把导入放在
    函数里，缺依赖时只有贯通档报错，且报的是「选了贯通档但依赖缺失」
    这个真实原因，不是一句模块找不到。
    """
    from openjiuwen.core.runner import Runner

    from agent_runtime.adapters.outbound.agentcore.handler import WorkflowAgentHandler

    # 构造参数是位置形式的「标识 + 执行器」，不是关键字形式。
    return WorkflowAgentHandler(WORKFLOW_ID, Runner)


# ── 共享存储 ────────────────────────────────────────────────────


class _TokenAuthorizer:
    """回调鉴权：请求头里的令牌与配置值相符即放行。

    **只为让回调入口在黑盒下可测**。真实宿主须用签名校验、双向证书一类的机制——
    共享令牌一旦泄漏，任何人都能触发回灌。

    **默认不注入**：未配置令牌时装配根本不构造本件，回调入口一律拒绝，
    与生产默认一致。要测回调三态的测试人员显式配 `RUNTIME_CALLBACK_TOKEN`。
    """

    def __init__(self, expected: str) -> None:
        self._expected = expected

    async def authorize(self, headers: dict, body: dict) -> bool:
        for key, value in (headers or {}).items():
            if key.lower() == "x-callback-token":
                return str(value) == self._expected
        return False


def _build_callback_authorizer() -> Any:
    token = os.environ.get("RUNTIME_CALLBACK_TOKEN", "")
    return _TokenAuthorizer(token) if token else None


class _InProcessCallbackCache:
    """回调判重存储的进程内实现。

    **只为让回调端点在黑盒下可达**。生产宿主须接共享存储——判重要跨副本才有意义，
    进程内实现在多副本下等于没有判重。
    """

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}

    async def setnx(self, key: str, value: Any, *, ttl_s: int | None = None) -> bool:
        if key in self._data:
            return False
        self._data[key] = value
        return True

    async def get(self, key: str) -> Any:
        return self._data.get(key)

    async def set(self, key: str, value: Any, *, ttl_s: int | None = None) -> None:
        self._data[key] = value

    async def delete(self, key: str) -> None:
        self._data.pop(key, None)

    # ── 端口协议的其余成员 ──
    # **真做而非假装**：内存字典的读写语义与真实客户端同构。装配期按契约校验，
    # 缺一项即被拒收——此前只实现了四个方法，参考宿主因此 import 即崩，
    # 而部署级探针跑的是另一套服务端，正好绕开这里。
    async def setex(self, key: str, ttl_s: int, value: Any) -> None:
        self._data[key] = value

    async def exists(self, key: str) -> bool:
        return key in self._data

    async def write_externally_governed(self, key: str, value: Any) -> None:
        self._data[key] = value

    async def mget(self, keys: list) -> list:
        return [self._data.get(k) for k in keys]

    def scan(self, match: str, *, count: int = 100):  # noqa: ANN201
        if not match.endswith("*"):
            raise NotImplementedError(f"本实现只支持尾部通配，实得 {match!r}")
        prefix = match[:-1]

        async def _iter():
            for key in list(self._data):
                if key.startswith(prefix):
                    yield key.encode("utf-8")

        return _iter()

    async def aclose(self) -> None:
        """无底层连接可释放——空实现是「确实没事可做」，不是登记态桩。"""


def _build_session_store() -> Any:
    """接共享存储。未配置即返回空。

    **未配置不是错误**：那表示宿主没有共享存储，会话上下文写侧随之静默跳过，
    南向读到空上下文并走既有兜底。混部验证才需要它——两侧要看见同一批键。
    """
    url = os.environ.get("RUNTIME_REDIS_URL")
    nodes = [x.strip() for x in os.environ.get("RUNTIME_REDIS_NODES", "").split(",") if x.strip()]
    if not url and not nodes:
        return None

    from agent_runtime.adapters.outbound.cache_redis.factory import (
        CacheConfig,
        ClusterConfig,
        build_cache_store,
    )
    from agent_runtime.adapters.outbound.session.shared_keys import SharedSessionStore

    # **经工厂装配，不直接构造实现类**：此前这里直接 new 单机实现，绕开了工厂，
    # 于是韧性包装（有界重试与退避）、启动策略日志、端点类型选择在**真实部署里
    # 一条都不生效**——这些能力全部实现于工厂之内，而唯一真正装 Redis 的地方不经过它。
    if nodes:
        # 集群形态由工厂自建客户端（口令与超时随之走工厂的脱敏与统一超时）。
        config = CacheConfig(
            endpoint_type="cluster",
            cluster=ClusterConfig(
                nodes=nodes,
                decrypted_password=SecretValue(os.environ.get("RUNTIME_REDIS_PASSWORD", "")),
            ),
        )
        return SharedSessionStore(build_cache_store(config))

    # 单机以连接串配置：工厂支持注入底层客户端，保留本形态的同时拿到全部装配能力。
    import redis.asyncio as aioredis

    # `url` 可能为空（未配置）——**不把空值交给客户端工厂**：它会在连接时
    # 才抛，错因指向网络而不是配置缺失。
    if not url:
        raise RuntimeError("未配置 Redis 连接串，也未配置集群节点——共享会话存储无法装配")
    return SharedSessionStore(build_cache_store(CacheConfig(), client=aioredis.from_url(url)))


# ── 装配 ────────────────────────────────────────────────────────


#: `skill_hub` 子树的环境变量前缀。**由配置命名空间推出**，与配置文件里的键
#: `openjiuwen.service.skill_hub.*` 一一对应（`bootstrap/config/loader.py` 的
#: `DEFAULT_ENV_PREFIX`）。不另起一套简写：同一件事有两个名字时，照文档配的人
#: 和照代码配的人会配到不同的地方去，而两边都不报错。
_SKILL_HUB_ENV_PREFIX = "OPENJIUWEN__SERVICE__SKILL_HUB"

#: 可选的配置文件路径。**配置文件与环境变量都要能用**：前者是基线（进版本库、
#: 可评审），后者是部署时最后一道调整手段，环境变量覆盖文件（加载器定的合并顺序）。
#: 此前本宿主只认环境变量，部署方把值写进配置文件时一项都不生效且不报错。
_CONFIG_FILE = os.environ.get("OPENJIUWEN__SERVICE__CONFIG_FILE", "").strip()


def _skill_hub_env(name: str, default: str = "") -> str:
    """读一个 `skill_hub` 配置项的原始环境变量值。

    **只用于绑定之前的开关判定**（`enabled`）——其余字段一律交给配置加载器绑，
    见 `_build_skill_hub_coordinator`。
    """
    return os.environ.get(f"{_SKILL_HUB_ENV_PREFIX}__{name}", default).strip()


def _build_skill_hub_coordinator() -> Any:
    """按环境变量装 Skill Hub 取材协调器；**未启用即返回空**。

    形态与共享存储那一节同构：宿主读环境、构造配置对象、经工厂装配，不直接构造实现类。

    **三样东西由本处（组合根）给，工厂不自己去拿**：

    | 给什么 | 为什么不在工厂里拿 |
    |---|---|
    | 移交实现 | 工厂落在 Skill Hub 子包，跨包构造框架子包的件会踩同层横向依赖（详设 §3.3） |
    | 凭据解密实现 | 它在基座，而适配层不得反向依赖基座 |
    | 已发现的扩展条目 | 同上 |

    这三样都在基座或另一个适配子包里，而组合根本就是允许同时看见它们的那一层。
    """
    from agent_runtime.ports.skill_hub import SkillHubConfig

    # **先绑配置，再判开关，最后才导入实现件**。三步的顺序各有理由：
    #
    # | 步 | 为什么在这个位置 |
    # |---|---|
    # | 绑配置 | 交给加载器，不逐字段手抄——手抄读不到配置文件，且 `fetch`/`retry` 两组嵌套字段在手抄里根本不出现 |
    # | 判开关 | 必须在绑定**之后**：判在前面时读的只有环境变量，配置文件里的 `enabled` 会被直接挡掉 |
    # | 导入实现件 | 必须在判开关**之后**：未启用时不该为它付导入代价。省下的是 `agentcore.skill_install` 与 `bootstrap.discovery` 两个——`skillhub` 包本身由 `_build_handler` 无条件导入，不在省下的那部分里 |
    #
    # 工厂自己也判 `enabled`（`adapters/outbound/skillhub/factory.py` 的 `build_skill_hub_coordinator`），
    # 本处这一判**不是为了正确性，是为了不导入**——删掉它行为一字不变，只是白导入三个模块。
    sources = (ConfigSource(SourceKind.FILE, _CONFIG_FILE),) if _CONFIG_FILE else ()
    config = ConfigLoader(env_prefix=_SKILL_HUB_ENV_PREFIX).load(SkillHubConfig, sources=sources)
    if not config.enabled:
        # **未配置即整条链路不装配**——不是「装一个关掉的」。行为与没有本特性时逐字相同。
        return None

    from agent_runtime.adapters.outbound.agentcore.skill_install import (
        AgentCoreSkillInstaller,
    )
    from agent_runtime.adapters.outbound.skillhub import (
        SKILL_HUB_PROVIDER_EXTENSION_POINT,
        build_skill_hub_coordinator,
    )
    from agent_runtime.bootstrap.config.loader import resolve_decryptor
    from agent_runtime.bootstrap.discovery import ExtensionDiscoverer

    return build_skill_hub_coordinator(
        config,
        installer=AgentCoreSkillInstaller(),
        decryptor=resolve_decryptor(
            os.environ.get("OPENJIUWEN__SERVICE__CREDENTIAL__DECRYPTOR", "")
        ),
        discovered=ExtensionDiscoverer().discover(SKILL_HUB_PROVIDER_EXTENSION_POINT),
    )


def _build_handler(inner: Any = None) -> Any:
    """装出执行处理器，并按配置决定要不要在它外面套一层 Skill Hub 代理。

    **套不套在这一处决定，不在处理器实现里**：装饰是加一层，不是替换——内层是谁、
    怎么执行，套之前套之后完全一样。未启用时 `wrap_with_skill_hub` 原样返回内层，
    对象图与没有本特性时逐字相同（详设 §11.2 新增面表第三行）。
    """
    from agent_runtime.adapters.outbound.skillhub import wrap_with_skill_hub

    # **注入的处理器优先**：兼容入口把存量宿主 Agent 装成处理器交进来，其余装配
    # （共享存储、配置、协议入口、Skill Hub 装饰）与两档内建后端逐字相同。
    if inner is not None:
        pass
    elif BACKEND == "agentcore":
        inner = _build_agentcore_handler()
    elif BACKEND != "fixture":
        raise ValueError(f"RUNTIME_BACKEND 取值须为 fixture 或 agentcore，收到 {BACKEND!r}")
    else:
        inner = _FixtureHandler()
    return wrap_with_skill_hub(inner, _build_skill_hub_coordinator())


def create_app(*, handler: Any = None) -> FastAPI:
    """把两个入口拼成一个进程。

    `handler` 给定时替换内建后端——兼容入口据此把存量宿主 Agent 装进来；
    不给则按 `RUNTIME_BACKEND` 选内建两档。其余装配不随之改变。

    **自定义 REST 应用作为外壳、标准协议应用作为子应用挂载**：存量的对外形态
    就是这样——两条入口同一进程同一端口，调用方按路径区分。拆成两个进程会
    改变调用方的连接目标，那是对外可观察的变化。
    """
    handler = _build_handler(handler)
    session_store = _build_session_store()

    # **标准协议应用作为主应用，自定义 REST 路由并入它**——不是反过来。
    #
    # 曾用过反过来的形态（REST 应用为主、协议应用当子应用挂到 `/a2a`），
    # 它有两处会静默失效，都是实测撞出来的：
    #
    # | 失效 | 表现 |
    # |---|---|
    # | 站点根的两条卡片端点被挂载前缀叠加 | 站点根 404，而进程内判据全绿 |
    # | 无尾斜杠的协议路径不注册 | `POST /a2a` 回 307 而非直接承载 |
    #
    # 二者的根因相同：runtime 只有在自己持有挂载前缀时才知道该往哪注册。
    # 把前缀交回给它，两处随之自愈，宿主也不必再替它补注册（H-SERVE-8 因此不适用于本装配）。
    # 状态缓存的装配读的是同一份配置文件（`OPENJIUWEN__SERVICE__CONFIG_FILE`）。
    # 未配置该段时两者皆空，下面按空值退回进程内实现。
    config_sources = (ConfigSource(SourceKind.FILE, _CONFIG_FILE),) if _CONFIG_FILE else ()
    # **取三元组形态**：第三样是 Task 快照数据库档的初始化钩子。该档默认关，钩子为空，
    # 本装置与此前逐字一致；配置里把 `runtime_db.runtime_db_enabled` 打开时钩子非空，
    # 必须挂进下面的 `init_hooks`——那一档要在启动期连库建表，漏挂即在首次使用时报错。
    cache_task_store, cache_client, task_store_init = build_a2a_stores_with_init(config_sources)

    # **整份运行时配置交给组合根**。不传就是不读配置——SDK 嵌在宿主进程里，
    # 去哪找配置文件由宿主决定；本装置的决定是「配了 `OPENJIUWEN__SERVICE__CONFIG_FILE`
    # 就读它，没配就一份都不读」。
    #
    # 缺这一行时，配置类有、加载器有、判据也验了解析，**而没有任何装配路径去读它**：
    # 宿主照文档写一份配置文件，一个字都不生效，且不报错、不告警。
    runtime_config = ConfigLoader().load(RuntimeConfig, sources=config_sources)

    # **凭据解密器按配置选取**。未配置即内建透传；配了名字却发现不到就在这里抛，
    # 不回落——回落到透传会让宿主以为密文已解密，而实际把密文原样传给下游连接，
    # 表现为运行期认证失败，排查时不会有人想到解密器压根没装上。
    #
    # **返回值不接**：本调用要的就是它在启动期抛出的那次失败，解密器本身由
    # `create_a2a_app` 内部按同一份配置再取一次。接了返回值反而读不出这层意思——
    # 一个赋了值却没人用的名字，看起来像是漏接了线。
    resolve_credential_decryptor(runtime_config)

    built_app = create_a2a_app(
        handler,
        name=AGENT_ID,
        description="agent runtime 黑盒验证装配",
        version="1.0.0",
        url=SELF_URL,
        session_store=session_store,
        mount_path="/a2a",
        # **状态缓存按配置装配**：配置文件里出现 `openjiuwen.service.middleware` 段
        # 就用 Redis 支持的 TaskStore 与回调判重存储，缺席则退回下面的进程内实现。
        #
        # 这一行是配置模型的**唯一生产消费点**。此前它不存在，于是按详设 §6 写一份
        # 配置文件不会让 Redis 接入生效——工厂在仓内零生产调用方，配置是死配置。
        # 上游没有这个缺口：Spring 的条件装配替宿主拼好了这条链。
        config=runtime_config,
        task_store=cache_task_store,
        # 回调接收入口是**条件注册**：不注入判重存储就不建路由，因为无幂等的回调接收
        # 会在重试时重复回灌。装置注入一个进程内实现，使该端点在黑盒下可被验证——
        # 不注入的话它恒 404，而「没配这个能力」与「能力做丢了」两种情形读数相同。
        #
        # 配了 Redis 就用它——回调判重跨实例才成立：多副本部署下，
        # 重试打到另一个实例时进程内的判重表是空的，重复回灌照样发生。
        push_callback_cache=cache_client or _InProcessCallbackCache(),
        # 未配令牌时为空，回调入口一律拒绝——与生产默认一致。
        push_callback_authorizer=_build_callback_authorizer(),
        # 贯通档须在处理器启动前注册工作流；契约档无钩子。
        # **不接这一条，贯通档会在首次调用时取到空的工作流实例**——
        # 而那时的错误信息不指向「工作流没注册」。
        # Task 快照数据库档的初始化钩子并入同一序列（默认关时它是空的，序列不变）。
        # **排在工作流注册之前**：存储要先能用，处理器启动后才会有请求落到它上面。
        init_hooks=(
            tuple(h for h in (task_store_init,) if h is not None)
            + ((_init_workflow,) if BACKEND == "agentcore" else ())
        ),
    )
    # 自定义 REST 路由并入同一应用：存量的对外形态就是两条入口同进程同端口，
    # 调用方按路径区分。拆成两个进程会改变调用方的连接目标，那是对外可观察的变化。
    #
    # **两条入口共用同一个编排器**：取协议入口工厂导出的那一个（宿主义务 H-SERVE-8 同类）。
    # 权威 `CL-d97028854c2a` 与 `CL-81951e981550` 要求自定义 REST 入口复用标准执行链路、
    # 不得形成私有执行路径或私有状态 owner。
    #
    # 此处曾另建一个，代价有两项：其一，批次执行件只装在协议入口那一侧，于是同一 handler
    # 同一输入在 REST 入口上任何远端委派都变成 `REMOTE_ORCHESTRATION_NOT_CONFIGURED`；
    # 其二，在途流登记表成了两份，REST 侧的流不进关停排水。共用之后两项同时消失。
    built_app.include_router(
        build_rest_router(
            MobileBankChannel(),
            built_app.state.orchestrator,
            session_store=session_store,
            # **共用协议入口那一个任务存储**：REST 入口的续接状态因此落在标准 Task 上，
            # 与协议入口读写同一处（权威 `CL-81951e981550`）。不传则退回入口自持的登记表，
            # 两条入口的等待态互相看不见。
            task_store=built_app.state.task_store,
        )
    )

    @built_app.get("/health")
    async def _health(success: str | None = None) -> Any:
        """健康检查。

        **响应体与存量逐字节一致**——存量的调用方（编排器、探活配置）按它判活，
        形态变了等于换了契约。`service` 位的取值取自存量的服务名，不是本装配的名字。
        """
        # **`?success=<x>` 原样回显那一路也要有**：存量
        # `.legacy-oracle/applications/a2a_service/app.py` 的
        # `health_check(success: str = None)` 给了这个参数就把该串原样返回。
        # 探活工装带上它时拿到的必须是同一种形态——此前只实现了默认那一路，
        # 而这段注释已经写着「与存量逐字节一致」。
        # 独立复核 2026-08-18 实测抓出（第七轮·对外兼容 V-3）。
        if success is not None:
            return success
        return {"status": "healthy", "service": "A2A Service"}

    # ── 探测端点：履行宿主义务 H-LIFE-1B ────────────────────────────
    #
    # **不改 `/health`，另起两条路由**。H-LIFE-1B（MUST）要的是「宿主把 runtime 的
    # 只读就绪视图接到自己的探测路由上」，它没有指定那条路由叫什么——而 `/health`
    # 的响应体锁在对外兼容面上，存量的调用方按 `{"status":"healthy",...}` 判活，
    # 把就绪态混进去等于改契约。两条要求因此不冲突，只是落在不同端点上。
    #
    # 语义分开也是探测本身的要求：存活失败该重启副本，就绪失败只该摘流量。
    # 混在一个端点上，降级启动的副本要么被反复重启、要么持续收流量，两种都错。

    @built_app.get("/livez")
    async def _livez() -> Any:
        """存活探测：进程还在不在。失败意味着该重启这个副本。"""
        alive = built_app.state.readiness.is_process_up()
        return JSONResponse(
            {"status": "up" if alive else "down"},
            status_code=200 if alive else 503,
        )

    @built_app.get("/readyz")
    async def _readyz() -> Any:
        """就绪探测：Agent 装载完没有。失败意味着该摘流量，但不该重启。

        **降级启动时这里返回 503**：进程活着但接不了活。没有这条路由时，
        那个状态在编排器眼里不可观测——`/health` 恒 healthy，流量照打，
        而副本一个都处理不了。这正是 H-LIFE-1B 的失效后果那一列写的形态。
        """
        ready = built_app.state.readiness.is_agent_loaded()
        return JSONResponse(
            {"status": "ready" if ready else "not-ready"},
            status_code=200 if ready else 503,
        )

    return built_app


app = create_app()
