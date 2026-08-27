# coding: utf-8

"""按类路径动态加载处置方。

## 加载失败只记错误、不中断

一个处置方加载不了不该拖垮其余的注册——那样调用方看到的是「路由整个不工作」，
而实际只有一类目标缺处置方。缺的那类在分派时会明确报「没有注册处置方」，
问题钉在它真正影响到的那一次调用上。

## 构造参数按签名过滤

调用方一次传进来的参数是给所有处置方的并集，各自只取自己认得的那些。
不过滤会让不接受某参数的处置方在构造期报参数错误，而那不是它的问题。
"""
from __future__ import annotations

import importlib
import inspect
import logging
from typing import Protocol, runtime_checkable

_logger = logging.getLogger(__name__)


@runtime_checkable
class EventHandlerLike(Protocol):
    """处置方。**协议由消费方定义**——注册表只要求它能被调用来处置一条事件。"""

    async def handle(self, event: object, target: object, context: object) -> object:
        ...


class HandlerRegistry:
    """处置方注册表。"""

    def load_handlers(
        self, handler_config: dict[str, str], **kwargs: object
    ) -> dict[str, EventHandlerLike]:
        """按「目标类型 → 类路径」加载并构造处置方实例。"""
        handlers: dict[str, EventHandlerLike] = {}
        for target_type, class_path in handler_config.items():
            try:
                module_path, class_name = class_path.rsplit(".", 1)
                handler_class = getattr(importlib.import_module(module_path), class_name)
                handlers[target_type] = handler_class(
                    **self._filter_kwargs(handler_class, kwargs)
                )
            except Exception as exc:
                _logger.error(
                    "处置方加载失败：目标=%s 类路径=%s err=%s", target_type, class_path, exc
                )
        return handlers

    @staticmethod
    def _filter_kwargs(handler_class: type, kwargs: dict[str, object]) -> dict[str, object]:
        """按构造函数签名过滤参数。**接受可变关键字参数的照单全收**。"""
        try:
            params = inspect.signature(handler_class).parameters
        except (TypeError, ValueError):
            return {}
        if any(p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()):
            return dict(kwargs)
        return {name: value for name, value in kwargs.items() if name in params}
