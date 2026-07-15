"""A4 (#9): LLM prompt JSON 紧凑化 — 单元测试。"""

from __future__ import annotations

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    _dumps_for_prompt,
)


def test_dumps_for_prompt_is_compact() -> None:
    """塞入 prompt 的 JSON 紧凑：无缩进、无多余空格。"""
    data = [{"op": "replace", "target": "SKILL.md", "content": "中文内容"}]
    out = _dumps_for_prompt(data)
    # 紧凑分隔：逗号后无空格、冒号后无空格、无换行缩进
    assert ", " not in out
    assert ": " not in out
    assert "\n" not in out
    # 中文不转义
    assert "中文内容" in out
    assert "\\u" not in out


def test_dumps_for_prompt_roundtrip() -> None:
    """紧凑 JSON 仍可正确解析回原结构。"""
    import json

    data = [{"a": 1, "b": [2, 3], "c": "x"}]
    out = _dumps_for_prompt(data)
    assert json.loads(out) == data


def test_dumps_for_prompt_smaller_than_pretty() -> None:
    """紧凑体积严格小于 pretty（indent=2）版本。"""
    import json

    data = [{"op": "replace", "target": "SKILL.md", "content": "x"} for _ in range(20)]
    compact = _dumps_for_prompt(data)
    pretty = json.dumps(data, ensure_ascii=False, indent=2)
    assert len(compact) < len(pretty)
