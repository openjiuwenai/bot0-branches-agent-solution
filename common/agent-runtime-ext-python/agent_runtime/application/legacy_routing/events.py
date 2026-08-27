# coding: utf-8

"""路由这一侧的数据载体：归一事件、路由上下文、路由目标。

**不进领域层**：根设计把领域层定为封闭清单（三个权威数据对象加任务状态机）。
这三者是路由判定的输入与输出，换掉路由实现就不存在，不构成领域概念。

**用标准库的数据类而非校验框架**：应用层不依赖框架是洋葱的分层约束。
存量在此处用了校验框架，那是它的选择；本层要的只是「带默认值的字段容器」，
标准库够用，且换掉框架不必改这一层。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class NormalizedEvent:
    """归一后的事件。`metadata` 里带来源与帧类型，是判定的主要依据。"""

    type: str
    data: dict[str, object] = field(default_factory=dict)
    metadata: dict[str, object] = field(default_factory=dict)


@dataclass
class RouteContext:
    """判定所需的会话与任务上下文。"""

    task_id: str = ""
    current_task: Optional[object] = None
    conv_id: str = ""
    root_task_id: str = ""
    agent_key: str = ""
    #: **未指定任务标识时走兼容路径**：老版本调用方不传它，
    #: 那时只能靠远端任务标识找回远端，找不到就回本地。
    is_specify_task: bool = True


@dataclass
class RouteTarget:
    """判定结果。"""

    type: str
    agent_key: str = ""
