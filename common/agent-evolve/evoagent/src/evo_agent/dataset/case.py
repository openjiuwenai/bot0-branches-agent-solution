"""EvoCase — 数据集 case 适配层。

在 EvoAgent 和 agent-core Case 之间桥接两种格式：

- **EvoCase 格式**（平台下发）：``id`` + ``inputs: list[dict]`` + ``expected_behavior``
- **Case 格式**（agent-core）：``case_id`` + ``inputs: dict`` + ``label: dict``

``parse_evo_cases()`` 解析原始 JSON → EvoCase 列表，
``evo_case_to_case()`` 将 EvoCase 转为 agent-core Case。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from openjiuwen.agent_evolving.dataset import Case


@dataclass(frozen=True)
class EvoCase:
    """EvoAgent 内部 case 表示。

    Attributes:
        case_id: 唯一标识。
        queries: 用户输入查询列表（从 inputs[].content 提取）。
        extra_data: 场景级附加数据（role_id 等）。
        expected_behavior: 期望行为描述。
    """

    case_id: str
    queries: tuple[str, ...]
    extra_data: dict[str, Any] = field(default_factory=dict)
    expected_behavior: str = ""

    def __post_init__(self) -> None:
        """将 queries 规范化为 tuple（兼容 list 输入）。"""
        if not isinstance(self.queries, tuple):
            object.__setattr__(self, "queries", tuple(self.queries))


def parse_evo_cases(raw: list[dict[str, Any]]) -> list[EvoCase]:
    """将原始 JSON 数组解析为 EvoCase 列表。

    跳过 inputs 为空的 case。忽略 ``type``、``source_trajectory`` 等额外字段。

    ``inputs`` 支持两种格式：
    - 字符串数组：``["帮我推荐理财产品", ...]``
    - 消息字典数组：``[{"role": "user", "content": "..."}]``
    """
    cases: list[EvoCase] = []
    for item in raw:
        inputs_list = item.get("inputs", [])
        if not inputs_list:
            continue

        # 支持两种格式：字符串数组 和 消息字典数组
        if isinstance(inputs_list[0], str):
            queries = tuple(s for s in inputs_list if s)
        else:
            queries = tuple(
                msg.get("content", "")
                for msg in inputs_list
                if msg.get("role") == "user" and msg.get("content")
            )
            if not queries:
                # fallback: 如果没有 role=user 的消息，取所有 content
                queries = tuple(msg.get("content", "") for msg in inputs_list if msg.get("content"))
        if not queries:
            continue

        cases.append(
            EvoCase(
                case_id=str(item.get("id", "")),
                queries=queries,
                extra_data=item.get("extra_data", {}),
                expected_behavior=item.get("expected_behavior", ""),
            )
        )
    return cases


def evo_case_to_case(evo: EvoCase) -> Case:
    """将 EvoCase 转为 agent-core Case。

    映射规则：
    - ``case_id`` → ``case_id``
    - ``queries[0]`` → ``inputs["query"]``
    - ``queries`` (list) → ``inputs["queries"]``
    - ``extra_data`` → ``inputs["extra_data"]``（保留供 optimizer 使用）
    - ``expected_behavior`` → ``label["expected_result"]``
    """
    first_query = evo.queries[0] if evo.queries else ""
    return Case(
        case_id=evo.case_id,
        inputs={
            "query": first_query,
            "queries": list(evo.queries),
            "extra_data": evo.extra_data,
        },
        label={"expected_result": evo.expected_behavior},
    )


def merge_extra_data(
    base: dict[str, Any],
    case_level: dict[str, Any],
) -> dict[str, Any]:
    """合并场景级和 case 级 extra_data，case 级覆盖场景级。"""
    return {**base, **case_level}
