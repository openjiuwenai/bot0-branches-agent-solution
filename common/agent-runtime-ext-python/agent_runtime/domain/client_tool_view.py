# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""每次调用携带的端侧工具视图（`CL-653e3ecd9129`／`CL-cea22dc93321`）。

## 这是什么

一次调用里客户端声明「我这边现在有哪些工具可用」。它是**请求级**的事实，不是装配期
的配置：同一个 Agent 面对不同客户端、不同页面、不同插件状态，看到的工具面本就不同。

## 为什么放在领域层

视图是一个**值**——一次调用的工具面。它不知道自己从 HTTP 请求来、也不知道要存进
Redis 还是内存；解析入站报文是适配层的事，绑定到 Task 是编排层的事。本模块只定义
「工具视图是什么」与「什么样的视图不合法」，不导入任何适配层或框架类型。

## wire 形态取自上游

键名与解析规则逐项对齐 `agent-solution@common` 的
`common/agent-runtime-ext-java/.../external/ClientToolRail.java`：
`metadata["clientTools"]` 为数组，每项为对象，取 `name`（必填）、`description`、
`inputSchema`。**沿用上游键名不是随大流**——客户端已经按这个键在发，换个名字等于
让现有客户端的工具面静默变空，而没有任何东西会报错。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Iterable, Mapping

from agent_runtime.domain.context import ServeRequest

#: 工具面在调用元数据里的键。取自上游 `ClientToolRail` 的 `CLIENT_TOOLS`。
CLIENT_TOOLS_KEY = "clientTools"


class ToolViewMalformed(ValueError):
    """客户端发来的工具面形态不合法。

    与内部错误分开是有意的：这一类该回客户端错误（是它发的东西不对），
    内部错误该回服务端错误。抛通用异常会让上层分不清这两者。
    """


class ToolNameConflict(ToolViewMalformed):
    """工具名冲突：客户端内部重名，或客户端工具与服务端工具重名。

    后者比前者更危险——它让执行链路把一个本该在服务端跑的调用投影给客户端
    （或反过来），**错的是执行位置，不是名字**。
    """


@dataclass(frozen=True)
class ClientTool:
    """工具面里的一项。三个字段与上游 `ToolInfo` 一一对应。"""

    name: str
    description: str = ""
    parameters: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ClientToolView:
    """一次调用的完整工具面。

    **不可变**：它是这一次调用的事实，执行链路读它、不改它。`tools` 用只读映射
    包一层，避免拿到引用后就地改写。
    """

    tools: Mapping[str, ClientTool] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "tools", MappingProxyType(dict(self.tools)))

    def names(self) -> tuple[str, ...]:
        """按声明顺序返回工具名——顺序取自客户端的声明序，不排序。"""
        return tuple(self.tools)

    def get(self, name: str) -> ClientTool | None:
        return self.tools.get(name)

    def is_empty(self) -> bool:
        return not self.tools


def merge_tool_faces(
    server_tools: Iterable[str], view: ClientToolView
) -> tuple[str, ...]:
    """合并服务端工具面与本次调用的客户端工具面，**服务端在前、客户端追加在后**。

    次序取自上游 `ClientToolRail.beforeModelCall`：
    `merged = new ArrayList<>(current); merged.addAll(visibleTools)`。

    **次序不是风格问题**：客户端工具由调用方声明，插到前面等于让调用方影响部署方
    工具的相对优先级——模型选工具时受列表次序影响，那会让一次调用改变部署方的编排。

    抛 `ToolNameConflict`：客户端工具与已注册的服务端工具重名。这是**绑定期**那道，
    与解析期的视图内查重是两道不同的关——输入面不同（一个是本次视图内部，一个是
    视图与 Agent 实例已有的工具面），上游 `openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/external/ClientToolRail.java` 的 `parseVisibleTools` 与 `beforeModelCall` 各一道。
    """
    current = list(server_tools)
    shadowed = sorted(set(current) & set(view.names()))
    if shadowed:
        raise ToolNameConflict(
            f"客户端工具与服务端已注册工具重名：{'、'.join(shadowed)}——"
            "重名会让执行链路把调用投影到错误的执行位置"
        )
    return tuple(current) + view.names()


def _text(raw: object) -> str:
    """取字符串值，非字符串一律归空串。

    参数标注用 `object` 而不是 `Any`：这里的入参是客户端 JSON 里的任意值，
    **确实不透明，但不透明不等于放弃类型检查**——`Any` 会让类型检查器对后续
    每一次使用都放行，`object` 则要求先窄化（此处即 `isinstance`）才能当字符串用。
    这正是 `arch` 门禁那条「不得用 Any 抹掉」要防的。
    """
    return raw if isinstance(raw, str) else ""


def parse_client_tool_view(
    request: ServeRequest,
    server_tool_names: Iterable[str] | None = None,
) -> ClientToolView:
    """从标准调用的元数据里解析工具视图。

    参数 server_tool_names：宿主声明的服务端工具名。**不传就不做遮蔽检查**——
        宿主没声明时臆造一份出来，会把合法调用judge成冲突。

    抛 `ToolViewMalformed`：非数组、项非对象、缺 name、name 为空白。
    抛 `ToolNameConflict`：客户端内部重名，或与服务端工具重名。
    """
    raw = (request.metadata or {}).get(CLIENT_TOOLS_KEY)
    if raw is None:
        return ClientToolView()
    if not isinstance(raw, list):
        raise ToolViewMalformed(f"{CLIENT_TOOLS_KEY} 必须是数组，实为 {type(raw).__name__}")

    tools: dict[str, ClientTool] = {}
    for item in raw:
        if not isinstance(item, dict):
            raise ToolViewMalformed(f"{CLIENT_TOOLS_KEY} 的每一项必须是对象，实为 {type(item).__name__}")
        name = _text(item.get("name")).strip()
        if not name:
            raise ToolViewMalformed(f"{CLIENT_TOOLS_KEY}[].name 必填且不得为空白")
        if name in tools:
            raise ToolNameConflict(f"客户端工具重名：{name}")
        schema = item.get("inputSchema")
        tools[name] = ClientTool(
            name=name,
            description=_text(item.get("description")),
            parameters=dict(schema) if isinstance(schema, dict) else {},
        )

    if server_tool_names:
        shadowed = sorted(set(tools) & set(server_tool_names))
        if shadowed:
            raise ToolNameConflict(
                f"客户端工具与服务端工具重名：{'、'.join(shadowed)}——"
                "重名会让执行链路把调用投影到错误的执行位置"
            )

    return ClientToolView(tools=tools)
