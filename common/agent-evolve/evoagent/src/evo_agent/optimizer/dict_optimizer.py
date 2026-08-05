"""DictSkillDocumentOptimizer — dict-based trajectory 兼容层。

上游 SkillDocumentOptimizer 使用 Trajectory 对象走 pipeline，
EvoAgent 通过 Adapter sidecar 返回 cleaned-traces（dict 格式），
本中间层覆写 _format_single / _extract_participating_operators，
使 pipeline（_reflect → _format_batch → _format_single）兼容 dict 格式。

场景 optimizer（如 EDPAgentOptimizer）继承此类，无需关心格式差异。
"""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.optimizer.artifact_exporter import DictArtifactExporter
from evo_agent.optimizer.skill_document import SkillDocumentOptimizer


def _clip_text(value: Any, limit: int) -> str:
    """截断文本到指定长度。"""
    if value is None:
        return ""
    return str(value)[:limit]


def _get_tool_call_fn(tool_call: Any) -> dict[str, Any]:
    """从 tool_call message 条目中提取 function dict。

    返回 ``{"name": ..., "arguments": ...}`` 或空 dict。
    """
    if isinstance(tool_call, dict):
        fn = tool_call.get("function")
        return fn if isinstance(fn, dict) else {}
    return {}


class DictSkillDocumentOptimizer(SkillDocumentOptimizer):
    """dict-based trajectory 兼容层。

    覆写方法:
    - ``_format_single()`` — 处理 ``{"case_id", "messages": [...]}`` 格式
    - ``_extract_participating_operators()`` — 从 dict messages 提取 operator_id
    - ``__init__()`` — 替换 artifact_exporter 为 DictArtifactExporter
    """

    def __init__(self, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        # 替换上游 ArtifactExporter 为支持 dict 格式的版本
        exporter = self._artifact_exporter
        if exporter.enabled:
            self._artifact_exporter = DictArtifactExporter(
                str(exporter._output_dir),
                score_threshold=self._score_threshold,
                export_trajectories=self._artifact_export_trajectories,
            )

    def _format_single(
        self,
        trajectory: dict[str, Any],
        evaluated_case: EvaluatedCase,
        case: Case,
    ) -> str:
        """格式化单条 dict trajectory 为 analyst 可读文本。

        消费 ``{"case_id": str, "messages": [{"role": ..., "content": ...}, ...]}``
        格式，与 Adapter cleaned-traces 对齐。
        """
        max_chars = self._max_chars_per_traj
        lines: list[str] = []
        for msg in trajectory.get("messages", []):
            role = msg.get("role", "")
            content = msg.get("content", "") or ""
            if role == "system":
                continue
            if role == "user":
                lines.append(f"[user] {_clip_text(content, self._max_msg_chars)}")
            elif role == "assistant":
                if content:
                    lines.append(f"[assistant] {_clip_text(content, self._max_msg_chars)}")
                for tc in msg.get("tool_calls", []):
                    fn = _get_tool_call_fn(tc)
                    lines.append(
                        f"[action] {fn.get('name', '')}: "
                        f"{_clip_text(fn.get('arguments', ''), self._max_msg_chars)}"
                    )
            elif role == "tool":
                name = msg.get("name", "")
                lines.append(f"[obs]    {name}: {_clip_text(content, self._max_tool_result_chars)}")
        text = "\n".join(lines)
        if len(text) > max_chars:
            half = max_chars // 2
            text = text[:half] + "\n...[middle truncated]...\n" + text[-half:]
        return text

    @staticmethod
    def _extract_participating_operators(
        trajectory: dict[str, Any],
        valid_op_ids: list[str],
    ) -> list[str]:
        """从 dict trajectory 的 messages 中提取参与操作的 operator_id。

        通过匹配 tool_calls 中的 function name 与 valid_op_ids。
        未匹配到时保守归因到所有 operator。
        """
        valid_set = set(valid_op_ids)
        found: set[str] = set()
        messages = trajectory.get("messages", []) if isinstance(trajectory, dict) else []
        if not isinstance(messages, list):
            return list(valid_op_ids)
        for msg in messages:
            if not isinstance(msg, dict):
                continue
            for tc in msg.get("tool_calls", []):
                name = _get_tool_call_fn(tc).get("name", "")
                if name in valid_set:
                    found.add(name)
        return list(found) if found else list(valid_op_ids)
