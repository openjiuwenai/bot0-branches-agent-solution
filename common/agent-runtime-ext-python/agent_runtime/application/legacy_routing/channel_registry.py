# coding: utf-8

"""北向通道注册表：按路由键登记通道，按路径匹配回来。

## 零改码扩展

新增一条北向接口只需注册一个通道与它的路径模板，不改分派代码。
**路径模板与前缀分开存**：前缀用于快速筛选，模板用于取路径参数，
合成一个字符串之后两件事都要重新解析。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional, Protocol, runtime_checkable


@runtime_checkable
class ChannelLike(Protocol):
    """通道。**协议由消费方定义**——注册表只用得到解析这一个动作，
    照抄适配层的完整通道面会让本层依赖外层。
    """

    def parse_request(
        self,
        body: dict[str, Any],
        *,
        path_params: dict[str, Any],
        headers: Optional[dict[str, Any]] = None,
        params: Optional[dict[str, Any]] = None,
    ) -> object:
        ...


@dataclass(frozen=True)
class RouteSpec:
    """一条北向路由的登记项。"""

    route_key: str
    prefix: str
    path_template: str
    channel: ChannelLike


def _extract_path_params(path: str, spec: RouteSpec) -> Optional[dict[str, str]]:
    """按登记项的模板取路径参数。匹配不上返回空。

    **带前缀与去前缀两种形态都试**：模板有时写全路径、有时写前缀之后的那一段，
    只试一种会让另一种写法的登记项永远匹配不上。
    """
    if spec.prefix and not path.startswith(spec.prefix):
        return None
    candidates = [path]
    if spec.prefix:
        candidates.insert(0, path[len(spec.prefix):] or "/")
    for candidate in candidates:
        params = _extract_template_params(candidate, spec.path_template)
        if params is not None:
            return params
    return None


def _extract_template_params(path: str, template: str) -> Optional[dict[str, str]]:
    """逐段比对路径与模板。**段数不等即不匹配**——模板不做通配。"""
    if not template:
        return {}
    path_parts = [part for part in path.strip("/").split("/") if part]
    template_parts = [part for part in template.strip("/").split("/") if part]
    if len(path_parts) != len(template_parts):
        return None
    params: dict[str, str] = {}
    for actual, expected in zip(path_parts, template_parts, strict=True):
        if expected.startswith("{") and expected.endswith("}"):
            params[expected[1:-1]] = actual
        elif actual != expected:
            return None
    return params


def _load_channel(class_path: str) -> ChannelLike:
    """按类路径加载通道并实例化。**加载失败直接抛**——

    通道装不上时整条北向接口不可用，静默跳过会让它表现为 404，
    而那时看不出是配置写错还是路由没匹配。
    """
    import importlib  # noqa: PLC0415

    module_path, class_name = class_path.rsplit(".", 1)
    channel: ChannelLike = getattr(importlib.import_module(module_path), class_name)()
    return channel


class AdapterRegistry:
    """通道与路由的登记表。"""

    #: 未给路由键时的默认通道名，与存量一致。
    DEFAULT_ROUTE_KEY = "mobile_bank"
    #: 未配置通道时装的那一个，与存量一致。
    DEFAULT_CHANNEL_CLASS = "channels.mobile_bank_channel.MobileBankChannel"
    #: 未配置路由时的路径模板，与存量一致。
    DEFAULT_PREFIX = "/v1"
    DEFAULT_PATH_TEMPLATE = "/v1/{project}/agents/{agent_id}/conversations/{conversation_id}"

    def __init__(self) -> None:
        self._channels: dict[str, ChannelLike] = {}
        self._specs: dict[str, RouteSpec] = {}
        self._default_route_key = self.DEFAULT_ROUTE_KEY

    @classmethod
    def from_config(cls, config: Optional[dict[str, Any]]) -> "AdapterRegistry":
        """按配置装出注册表。**配置为空时装默认那一条**——

        零配置的部署要能起来，与存量一致；而「没配等于没有通道」会让
        默认部署直接 404，那种失败看起来像路由坏了。
        """
        registry = cls()
        data = config or {}
        registry._default_route_key = str(
            data.get("default_route_key") or data.get("default_channel") or cls.DEFAULT_ROUTE_KEY
        )
        channels = data.get("channels") or [
            {"name": cls.DEFAULT_ROUTE_KEY, "class": cls.DEFAULT_CHANNEL_CLASS}
        ]
        instances: dict[str, ChannelLike] = {}
        for item in channels:
            name = str(item["name"])
            channel = _load_channel(str(item["class"]))
            registry.register_channel(name, channel)
            instances[name] = channel
        routes = data.get("routes") or [
            {
                "route_key": registry._default_route_key,
                "prefix": cls.DEFAULT_PREFIX,
                "path_template": cls.DEFAULT_PATH_TEMPLATE,
                "channel": registry._default_route_key,
            }
        ]
        for route in routes:
            route_key = str(route["route_key"])
            registry.register(
                route_key,
                RouteSpec(
                    route_key=route_key,
                    prefix=str(route.get("prefix", cls.DEFAULT_PREFIX)),
                    path_template=str(route.get("path_template", cls.DEFAULT_PATH_TEMPLATE)),
                    channel=instances[str(route.get("channel", route_key))],
                ),
            )
        return registry

    def register_channel(self, name: str, channel: ChannelLike) -> None:
        self._channels[name] = channel

    def register(self, route_key: str, spec: RouteSpec) -> None:
        self._specs[route_key] = spec

    def get(self, route_key: Optional[str] = None) -> RouteSpec:
        """按路由键取登记项；不给键时取默认那条。"""
        key = route_key if route_key is not None else self._default_route_key
        if key in self._specs:
            return self._specs[key]
        if route_key is None and len(self._specs) == 1:
            return next(iter(self._specs.values()))
        raise KeyError(f"未注册的路由键：{key}")

    def get_channel(self, route_key: Optional[str] = None) -> ChannelLike:
        return self.get(route_key).channel

    def all_specs(self) -> dict[str, RouteSpec]:
        return dict(self._specs)

    def match_path(self, path: str) -> Optional[RouteSpec]:
        """匹配路径。**匹配不上时退回默认路由**——供内部分派用。

        对外入口不该用它（见 `match_route`）：退回默认会让未知路径也被受理，
        而受理之后的失败发生在业务里，看不出是路径写错了。
        """
        normalized = path or ""
        for spec in self._specs.values():
            if _extract_path_params(normalized, spec) is not None:
                return spec
        return self._specs.get(self._default_route_key)

    def match_route(self, path: str) -> tuple[Optional[RouteSpec], dict[str, str]]:
        """严格匹配并取出路径参数。**不退回默认**——

        对外入口用它，未知路径立即匹配不上，调用方拿到的是明确的「没有这条路径」。
        """
        normalized = path or ""
        for spec in self._specs.values():
            params = _extract_path_params(normalized, spec)
            if params is not None:
                return spec, params
        return None, {}
