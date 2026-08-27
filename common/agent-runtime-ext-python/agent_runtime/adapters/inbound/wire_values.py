# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""落到 wire 上的值的规范化（两条入站适配器共用）。

## 它解决什么

同一份处理器输出，此前在三个地方有三种命运：

| 路径 | 遇到 `datetime` 时 |
|---|---|
| A2A（`chunk_mapper._data_part` → protobuf `Struct.update`） | 抛 `ValueError: Unexpected type`，异常穿到执行器兜底被包成内部错误，**整个 Task 失败**，已入队的内容帧一并作废 |
| 自定义 REST（`json.dumps(..., default=str)`） | 落到 wire 上的是 `2026-08-10 12:00:00`——**空格分隔，不是 ISO-8601 的 `T` 分隔** |
| 存量（`json.dumps(..., ensure_ascii=False)`，无兜底） | `TypeError` |

三者实测确证过。而这类值**真实会出现**——REST 侧那行注释本身就写着
「真实 agent-core 输出可能带 datetime 等非 JSON 原生类型（透传 data 里）」。

## 两条原则各要求了什么

- **生态融入**要求对外 wire 走语言中立的 A2A 形态。Python 的 `str()` 不是：
  `datetime` 渲染成空格分隔、任意对象渲染成 `<pkg.Cls object at 0x7f…>`（含内存地址）。
  Java 或 JS 客户端按时间戳解析那个字段会失败。
- **两条入口的执行与对外语义须归一**（`CL-637f381ea25c`）。一侧硬失败、另一侧兜底，
  是同一份输出在两条入口上的两种结果。

## 规则

1. `dict` / `list` / `tuple` 递归规范化；`tuple` 落为列表（JSON 无元组）
2. `str` / `bool` / `int` / `float` / `None` 原样——**`bool` 必须先于 `int` 判**，
   它是 `int` 的子类，顺序反了 `True` 会落成 `1`
3. `datetime` / `date` / `time` → `isoformat()`。**这不是自造形态，是 ISO-8601**，
   与上游 Java 侧的时间序列化同一标准，Java 与 JS 都能直接解析
4. `Decimal` → `float`；`UUID`、`Path`、`Enum` → 其字符串形式
5. 其余 → `str()`，**并记一条告警**

## 第 5 条为什么记告警

上游对应位置用 `GSON.toJson(data)` 把任意对象反射成 JSON 对象
（`openJiuwen/agent-runtime-java/service/agent-service-app/src/main/java/
com/openjiuwen/service/app/controller/a2a/ChunkMapper.java` 的 `isTerminalResult`）。Python 侧没有对等物——
按 `__dict__` 反射会把对象的内部字段整个泄漏到 wire 上，那是比形态不中立更糟的事。

故此处退到 `str()`，但**不静默**：落到这一支意味着有一类值正在以 Python 的形态出现在
对外报文里，那是需要被看见并逐类补进上面规则表的事实，不是可以一直兜下去的常态。
"""
from __future__ import annotations

import datetime
import decimal
import enum
import logging
import pathlib
import uuid
from typing import Any

_logger = logging.getLogger(__name__)

#: 原样落地的 JSON 原生标量。**`bool` 在 `int` 之前**——它是 `int` 的子类。
_PASSTHROUGH = (str, bool, int, float)


def json_safe(value: Any) -> Any:
    """把任意值规范化为 JSON 原生结构，形态语言中立。

    对已经是原生结构的输入是恒等变换——正常路径上没有额外代价，
    也就不存在「为了兜底把好数据改坏」的风险。
    """
    if value is None or isinstance(value, _PASSTHROUGH):
        return value
    if isinstance(value, dict):
        # 键也要规范化：protobuf 的 Struct 只接受字符串键，非字符串键会在那里抛。
        return {_safe_key(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return float(value)
    if isinstance(value, enum.Enum):
        return json_safe(value.value)
    if isinstance(value, (uuid.UUID, pathlib.PurePath)):
        return str(value)
    if isinstance(value, (bytes, bytearray)):
        # 二进制不进 wire 的文本面。解码不了就退到长度描述——**不吐乱码**。
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            _logger.warning("非文本二进制值落到 wire 规范化：%d 字节，以长度描述替代", len(value))
            return f"<{len(value)} bytes>"
    _logger.warning(
        "wire 规范化遇到未登记的类型 %s，退到字符串形式。"
        "该类型正在以 Python 的形态出现在对外报文里，应补进 `wire_values` 的规则表。",
        type(value).__name__,
    )
    return str(value)


def _safe_key(key: Any) -> str:
    """字典键归一为字符串。

    非字符串键在 protobuf `Struct` 那里同样会抛，而 JSON 对象的键本就只能是字符串。
    """
    return key if isinstance(key, str) else str(key)
