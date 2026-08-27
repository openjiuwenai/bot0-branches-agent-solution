# coding: utf-8

"""路由分派器：按事件来源选判定策略，再把结果交给注册的处置方。

## 两步是分开的

`route` 只判「该去哪」，`dispatch` 才「送过去」。分开的理由是判定可以被单独驱动
与断言——把两步合成一个方法时，要验判定就必须连带准备一整套处置方。

## 未知来源与未注册处置方都立即失败

不静默丢弃、也不退回默认。事件被丢掉时调用方看到的是「没有响应」，
而那时无从判断是没路由到、还是处置方没产出——立即失败把问题钉在发生的地方。
"""
from __future__ import annotations

import logging
from typing import Any, Callable, Optional

from agent_runtime.application.legacy_routing.events import (
    NormalizedEvent,
    RouteContext,
    RouteTarget,
)
from agent_runtime.application.legacy_routing.profiles import (
    RouteConfig,
    SourceRouteProfile,
)
from agent_runtime.application.legacy_routing.strategies import (
    LocalAgentSourceStrategy,
    RemoteAgentSourceStrategy,
    RequesterSourceStrategy,
    RouteStrategy,
)
from agent_runtime.application.legacy_routing.task_state import TaskStateManager

_logger = logging.getLogger(__name__)

#: 事件未标来源时按请求方处理——首轮请求的来源正是它。
_DEFAULT_SOURCE = "requester"


class RouteDispatcher:
    """按来源分派。"""

    def __init__(
        self,
        state_mgr: TaskStateManager,
        config: Optional[RouteConfig] = None,
        config_path: Optional[str] = None,
        local_agent_names: Optional[list[str]] = None,
    ) -> None:
        self._state_mgr = state_mgr
        self._strategy_cache: dict[str, dict[str, RouteStrategy]] = {}
        self._handlers: dict[str, dict[Optional[str], Callable]] = {}
        self._handler_instances: dict[str, object] = {}
        self._local_agent_keys: set[str] = set(local_agent_names or [])
        # **路径装载由装配层做**：读文件是框架能力，内层零框架依赖。
        # 给了路径时由装配层先装好再传进来；此处只接受已装好的配置对象。
        if config_path is not None and config is None:
            raise ValueError(
                "给了配置路径就必须同时给出已装载的配置对象——"
                "读文件属装配层职责，见 bootstrap/legacy_compat/route_config_loader.py"
            )
        self._config = config or RouteConfig()
        self._default_profile = self._config.default_profile
        self._profiles = self._config.profiles

    @property
    def config(self) -> RouteConfig:
        return self._config

    def apply_config(self, config: RouteConfig) -> dict[str, str]:
        """换一份配置。**换完清空策略缓存**——缓存里握的是旧参数造出来的策略。"""
        self._config = config
        self._default_profile = self._config.default_profile
        self._profiles = self._config.profiles
        self._strategy_cache.clear()
        self._ensure_strategies("")
        return self._config.handlers

    def register_handlers_from_config(self, **kwargs: object) -> None:
        """按配置里的类路径加载并注册处置方。配置为空时什么都不做。"""
        if not self._config.handlers:
            return
        from agent_runtime.application.legacy_routing.handler_registry import (  # noqa: PLC0415
            HandlerRegistry,
        )

        loaded = HandlerRegistry().load_handlers(self._config.handlers, **kwargs)
        for target_type, instance in loaded.items():
            self._handler_instances[target_type] = instance
            self.register_handler(target_type, instance.handle)

    def get_handler_instance(self, target_type: str) -> object | None:
        return self._handler_instances.get(target_type)

    def get_profile(self, agent_key: str) -> SourceRouteProfile:
        return self._profiles.get(agent_key, self._default_profile)

    def _ensure_strategies(self, agent_key: str) -> dict[str, RouteStrategy]:
        """按智能体造一组策略并缓存。**缓存按智能体分键**——不同智能体的参数不同。"""
        cached = self._strategy_cache.get(agent_key)
        if cached is not None:
            return cached
        profile = self.get_profile(agent_key)
        strategies: dict[str, RouteStrategy] = {
            "requester": RequesterSourceStrategy(
                self._state_mgr,
                profile.requester,
                self._local_agent_keys,
                self._config.max_cascade_depth,
            ),
            "local_agent": LocalAgentSourceStrategy(profile.local_agent),
            "remote_agent": RemoteAgentSourceStrategy(profile.remote_agent),
        }
        self._strategy_cache[agent_key] = strategies
        return strategies

    def register_handler(
        self, target_type: str, handler: Callable, source: Optional[str] = None
    ) -> None:
        """注册处置方。`source` 为空表示「该目标类型的兜底处置方」。"""
        self._handlers.setdefault(target_type, {})[source] = handler

    async def route(self, event: NormalizedEvent, context: RouteContext) -> RouteTarget:
        """只判去哪，不送。"""
        source = str(event.metadata.get("source") or _DEFAULT_SOURCE)
        strategies = self._ensure_strategies(context.agent_key or "")
        strategy = strategies.get(source)
        if not strategy:
            raise ValueError(f"Unknown source direction: {source}")
        target = await strategy.route(event, context)
        _logger.info(
            "路由决策：事件=%s 来源=%s → 目标=%s 智能体=%s",
            event.type, source, target.type, target.agent_key,
        )
        return target

    async def dispatch(self, event: NormalizedEvent, context: dict[str, Any]) -> object:
        """判定并交给处置方。"""
        route_context = RouteContext(
            task_id=str(context.get("task_id", "")),
            current_task=context.get("current_task"),
            conv_id=str(context.get("conv_id", "")),
            root_task_id=str(context.get("root_task_id", "")),
            agent_key=str(context.get("agent_key", "")),
            is_specify_task=bool(context.get("is_specify_task", True)),
        )
        target = await self.route(event, route_context)
        target_handlers = self._handlers.get(target.type)
        if not target_handlers:
            raise ValueError(f"No handler registered for target type: {target.type}")
        event_source = event.metadata.get("source")
        event_source = str(event_source) if event_source is not None else None
        # 先找该来源专属的处置方，没有再用兜底那个。
        handler = target_handlers.get(event_source) or target_handlers.get(None)
        if not handler:
            raise ValueError(
                f"No handler registered for target type '{target.type}' "
                f"with source '{event_source}'."
            )
        return await handler(event, target, context)
