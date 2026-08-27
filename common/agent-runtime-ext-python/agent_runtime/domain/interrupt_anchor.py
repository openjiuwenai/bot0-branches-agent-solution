# coding: utf-8

"""续接锚点在状态消息元数据里的落位约定。

## 为什么它在领域层

这两个键是**入站与出站共享的一份约定**：入站把中断的续接锚点写进
Task 状态消息的 metadata（`adapters/inbound/a2a/chunk_mapper.py`），
出站的总线投影从同一处读出来交给调用方
（`adapters/outbound/bus/task_projector.py`，`CL-a3a2249a79cf` 要求等待输入投影
带可恢复上下文引用）。

先前它定义在入站那一侧，出站直接 import 过去——同层横向依赖门禁当场阻断：
**两侧耦合在一个谁也不拥有的约定上**。谁改了那一侧的键名，
另一侧读不到锚点而不报错，症状是「调用方拿不到续接引用」，
而两处代码各自看都正常。

约定下沉之后，两侧都向内依赖同一份，没有一条横向的边。

## 键名为什么是这两个

与对标实现同名——同一个客户端可能同时对接两侧实现，
键名不一致会让「锚点是否可见」这件事在两侧表现不同。
"""
from __future__ import annotations

#: 续接锚点在状态消息 metadata 里的键。
INTERRUPT_METADATA_KEY = "_interrupt"

#: 锚点对象内承载恢复点标识的字段名。
RECOVERY_POINT_FIELD = "recovery_point_id"
