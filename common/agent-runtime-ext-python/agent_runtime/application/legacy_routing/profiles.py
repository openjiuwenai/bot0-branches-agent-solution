# coding: utf-8

"""三类来源各自的判定参数。

**参数化而不是写死**：挂起状态集合、委托事件类型、终态帧集合都是部署可调的，
写死之后换一种事件命名就要改代码。

**用标准库的数据类而非校验框架**：应用层不依赖框架（洋葱分层约束）。
"""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class RequesterSourceProfile:
    """来自请求方的事件。"""

    #: 挂起状态集合：任务处于其中之一才算续轮
    suspended_states: list[str] = field(default_factory=lambda: ["INPUT_REQUIRED", "AUTH_REQUIRED"])


@dataclass
class LocalAgentSourceProfile:
    """来自本地智能体的事件。"""

    #: 委托事件类型集合：命中即路由到远端智能体
    delegate_types: list[str] = field(default_factory=lambda: ["delegate", "sub_agent_dispatch", "multi_delegate"])
    #: 委托事件未指定目标时使用的远端智能体
    default_remote_agent: str = "versatile_adapter"


@dataclass
class RemoteAgentSourceProfile:
    """来自远端智能体的事件。"""

    #: 终态帧集合：命中即回本地智能体，其余回请求方
    terminal_frame_types: list[str] = field(default_factory=lambda: ["CONTROL_COMPLETED", "CONTROL_FAILED"])
    #: 任务状态到帧类型的映射
    frame_type_map: dict[str, str] = field(default_factory=lambda: {
            "COMPLETED": "CONTROL_COMPLETED",
            "FAILED": "CONTROL_FAILED",
            "INPUT_REQUIRED": "CONTROL_INPUT_REQUIRED",
            "AUTH_REQUIRED": "CONTROL_AUTH_REQUIRED",
            "SUBMITTED": "CONTROL_SUBMITTED",
            "WORKING": "CONTROL_WORKING",
            "CANCELED": "CONTROL_CANCELED",
            "REJECTED": "CONTROL_REJECTED",
            "ARTIFACT": "DATA",
        })
    #: 映射未命中时的帧类型
    default_frame_type: str = "CONTROL_UNSPECIFIED"


@dataclass
class SourceRouteProfile:
    """三类来源的判定参数合集。"""

    requester: RequesterSourceProfile = field(default_factory=RequesterSourceProfile)
    local_agent: LocalAgentSourceProfile = field(default_factory=LocalAgentSourceProfile)
    remote_agent: RemoteAgentSourceProfile = field(default_factory=RemoteAgentSourceProfile)


@dataclass
class RouteConfig:
    """路由配置：处置方注册、按智能体分设的判定参数、级联深度上限。"""

    #: 目标类型到处置方类路径的映射
    handlers: dict[str, str] = field(default_factory=dict)
    #: **按智能体分设**：同一部署里不同智能体的委托类型与默认远端可以不同，
    #: 共用一份参数会让其中一个的调整波及另一个。
    #: 智能体标识到判定参数的映射
    profiles: dict[str, SourceRouteProfile] = field(default_factory=dict)
    default_profile: SourceRouteProfile = field(default_factory=SourceRouteProfile)
    #: 级联查找深度上限。**必须有**：任务树可以成环，无上限时查找不返回。
    #: 级联查找的最大深度
    max_cascade_depth: int = 10
