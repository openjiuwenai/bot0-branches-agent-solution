# coding: utf-8

"""日志的请求级关联标识：本次调用的 Task 与会话（`CL-d8f7165a3839`）。

## 这一件解决什么

权威 `CL-d8f7165a3839` 逐字：「执行窗口内日志、trajectory 和上下文派生字段
**必须能关联 context、task、agent**。」

三个标识的**来源层次不同**，这决定了它们该怎么落：

| 标识 | 何时确定 | 该怎么记 |
|---|---|---|
| `task` | 每次调用 | 请求入口设一次，过滤器注入 |
| `context` | 每次会话 | 同上 |
| `agent` | **装配期，此后不变** | 装配时设一次，过滤器注入 |

**三个都统一注入，调用点一个字都不改**。逐处手写的代价在本仓可以实测：
一次扫描查出 26 处日志记了 task 却没记 context——而它们本来都能拿到，
只是写的时候没想起来。**靠每个作者每次都想起来，是一条必然会漏的路**，
漏了也不会有任何东西提醒，它看起来只是一条日志少了个字段。

新写的日志天然带上这三个字段，这是逐处手写给不了的性质。

## 为什么是过滤器而不是适配器

`logging.LoggerAdapter` 要求每个调用点换用适配器实例，那等于把改动摊回二十八处。
过滤器挂在 handler 上，对**已有的每一条日志记录**生效，调用点一个字都不用改。

## 它不做的事

**不改消息文本**：注入的是记录对象上的字段（`record.agent_id`），
由格式串决定要不要打印。部署方用结构化日志时它直接进 JSON 字段，
用普通格式串时可以在格式里加 `%(agent_id)s`。

**不兜底成空串以外的值**：拿不到身份时留空，不猜、不填「unknown」——
后者会让「没配置身份」和「身份就叫 unknown」在读数上无法分辨。
"""
from __future__ import annotations

import contextvars
from typing import Optional

#: 本次调用的 Task 与会话标识。**请求级作用域**：由入站适配件在受理时设，
#: 同一个事件循环里的并发请求各持一份（`contextvars` 的语义），互不串。
_TASK_ID: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "agent_runtime_task_id", default=None
)
_CONTEXT_ID: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "agent_runtime_context_id", default=None
)
#: 本次调用的链路标识。**存量运维按它捞整条链路的日志**
#: （`.legacy-oracle/applications/a2a_service/common/logger.py` 的 `bind_context`
#: 绑 trace／agent／conversation 三项，本模块此前只有后两项）。
#: 缺它时，同一次跨服务调用在本版这一段的日志无法与上下游拼起来——
#: 而拼链路正是这个标识存在的全部理由。
_TRACE_ID: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "agent_runtime_trace_id", default=None
)
#: 本 runtime 在服务面的身份。**装配期确定、此后不变**，但仍按上下文存——
#: 同进程可以并存多套 runtime（详设 `Feat-Func-000b` 的 V3 验收项要求各自独立），
#: 而进程级的存法（模块全局、记录工厂对象属性）都只有一份，
#: 后装配的那套会把先装的身份无条件覆盖，两边都不报错。
_AGENT_ID: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "agent_runtime_agent_id", default=None
)


def bind_call_identity(*, task_id: str = "", context_id: str = "", trace_id: str = "") -> None:
    """把本次调用的标识绑到当前执行上下文。

    **由入站适配件在受理时调一次**，此后该请求内的每一条日志都自动带上，
    调用点一个字都不用改。

    **空值不覆盖已有值**：一次调用里可能先拿到会话标识、后拿到 Task 标识，
    后一次绑定不该把前一次抹掉。
    """
    if task_id:
        _TASK_ID.set(str(task_id))
    if context_id:
        _CONTEXT_ID.set(str(context_id))
    if trace_id:
        _TRACE_ID.set(str(trace_id))


def bind_agent_identity(agent_id: str) -> None:
    """把本 runtime 的服务面身份绑到当前执行上下文。

    **由每套 runtime 在自己的受理点调**：装配期只是把值备着，
    真正生效是在请求进来、绑上下文的那一刻——那样同进程的两套 runtime
    各自的请求协程各持一份身份，互不覆盖。

    **空值不覆盖已有值**，与 `bind_call_identity` 同语义。
    """
    if agent_id:
        _AGENT_ID.set(str(agent_id))


def reset_call_identity() -> None:
    """把本次调用的三项标识清空——**给「一个执行流连跑多条独立请求」的入口用**。

    ## 为什么必须有这一件

    `bind_call_identity` 的语义是「空值不覆盖已有值」。那对**单次 HTTP 请求**是对的：
    一次调用里可能先拿到会话标识、后拿到 Task 标识，后一次绑定不该把前一次抹掉。

    **而总线消费者是在同一个协程里连着跑多条消息的**（`inbound/bus/consumer.py`
    的 `run_once` 逐条 `await`），上下文变量不随消息边界复位。于是第二条消息的
    信封若没带标识，读到的是**上一条的**——实测：

        消息1（带 conv-A / trace-A） → context_id='conv-A', trace='trace-A'
        消息2（correlationId 空、无 trace） → context_id='conv-A', trace='trace-A'

    运维按会话捞日志时，第二条消息的日志会挂在第一条的会话上。
    这是「runtime 实例对外无状态」最字面的反例（六原则第六条），
    由收官复核的状态外置检察官 2026-08-27 实测抓出。

    ## 用法

    **每条消息受理前调一次**，紧接着再 `bind_call_identity` 绑本条的值。
    HTTP 入口不需要它——那里一个请求一个协程，上下文天然隔离。
    """
    _TASK_ID.set(None)
    _CONTEXT_ID.set(None)
    _TRACE_ID.set(None)


def current_agent_identity() -> str:
    """读当前执行上下文里的 agent 身份；未绑定时返回空串。

    **不兜底成「unknown」之类的值**：那会让「没绑定」和「身份就叫 unknown」
    在读数上无法分辨。
    """
    return _AGENT_ID.get() or ""


def current_trace_identity() -> str:
    """读当前执行上下文里的链路标识；未绑定时返回空串。

    **不兜底**，与本模块其余读取件同语义：兜出来的值会被当成真的链路标识，
    而运维拿它去上下游捞日志时什么都捞不到，比字段为空更难查。
    """
    return _TRACE_ID.get() or ""


def current_call_identity() -> tuple[str, str]:
    """读当前执行上下文里的（task 标识, 会话标识）。

    供装配层的过滤器取值——**过滤器在 bootstrap，绑定在这里**：
    绑定由入站适配件在受理时调用，属适配层；装配由 `create_a2a_app` 调用，属装配层。
    放同一个模块时适配件就要依赖装配层，那是反向依赖、会成环
    （`arch` 门禁的 DEP-DIRECTION 正是抓这个）。
    """
    return _TASK_ID.get() or "", _CONTEXT_ID.get() or ""
