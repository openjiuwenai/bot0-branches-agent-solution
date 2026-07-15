"""AnswerExtractor 单元测试 —— auto-degrade：regex→json_path→LLM→空。"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from evo_agent.evaluator.offline.extractor import (
    METHOD_EMPTY,
    METHOD_JSON_PATH,
    METHOD_LLM,
    METHOD_REGEX,
    AnswerExtractor,
    ExtractionConfig,
    _parse_llm_value,
    _walk_json_path,
)


def _fake_model(content: str) -> AsyncMock:
    """返回一个 async invoke → AssistantMessage.content 的假 Model。"""
    mock = AsyncMock()
    mock.invoke = AsyncMock(return_value=SimpleNamespace(content=content))
    return mock


def _extractor(
    *,
    regex: str | None = None,
    json_path: str | None = None,
    model: AsyncMock | None = None,
    prompt: str = "extract: {prediction}",
) -> AnswerExtractor:
    return AnswerExtractor(
        ExtractionConfig(
            regex=regex,
            json_path=json_path,
            model=model or _fake_model("extracted-answer"),
            prompt=prompt,
        )
    )


def test_regex_hit_uses_regex() -> None:
    ext = _extractor(regex=r"capital is (.+?)\.")
    result = asyncio.run(ext.extract("The capital is Paris. Nice."))
    assert result.method == METHOD_REGEX
    assert result.extracted == "Paris"


def test_regex_no_group_returns_full_match() -> None:
    ext = _extractor(regex=r"Paris")
    result = asyncio.run(ext.extract("hello Paris world"))
    assert result.method == METHOD_REGEX
    assert result.extracted == "Paris"


def test_regex_miss_falls_back_to_llm() -> None:
    model = _fake_model("LLM-ANSWER")
    ext = _extractor(regex=r"no-match-pattern", model=model)
    result = asyncio.run(ext.extract("nothing here"))
    assert result.method == METHOD_LLM
    assert result.extracted == "LLM-ANSWER"


def test_json_path_hit() -> None:
    ext = _extractor(json_path="answer.value")
    raw = '{"answer": {"value": "Paris"}}'
    result = asyncio.run(ext.extract(raw))
    assert result.method == METHOD_JSON_PATH
    assert result.extracted == "Paris"


def test_json_path_miss_falls_back_to_llm() -> None:
    model = _fake_model("LLM-VAL")
    ext = _extractor(json_path="missing.path", model=model)
    result = asyncio.run(ext.extract('{"answer": "x"}'))
    assert result.method == METHOD_LLM
    assert result.extracted == "LLM-VAL"


def test_no_deterministic_config_uses_llm_directly() -> None:
    model = _fake_model("direct-llm")
    ext = _extractor(model=model)
    result = asyncio.run(ext.extract("some prediction text"))
    assert result.method == METHOD_LLM
    model.invoke.assert_awaited_once()


def test_llm_failure_yields_empty() -> None:
    model = AsyncMock()
    model.invoke = AsyncMock(side_effect=RuntimeError("boom"))
    ext = _extractor(model=model)
    result = asyncio.run(ext.extract("prediction"))
    assert result.method == METHOD_EMPTY
    assert result.extracted == ""


def test_llm_parses_json_list() -> None:
    model = _fake_model('["a", "b"]')
    ext = _extractor(model=model)
    result = asyncio.run(ext.extract("pred"))
    assert result.method == METHOD_LLM
    assert result.extracted == ["a", "b"]


def test_llm_empty_output_yields_empty() -> None:
    model = _fake_model("   ")
    ext = _extractor(model=model)
    result = asyncio.run(ext.extract("pred"))
    assert result.method == METHOD_EMPTY
    assert result.extracted == ""


def test_none_prediction_treated_as_empty_text() -> None:
    # prediction_field 缺失 → None → 当 "" 处理，regex 不命中走 LLM。
    model = _fake_model("x")
    ext = _extractor(model=model)
    result = asyncio.run(ext.extract(None))
    assert result.method == METHOD_LLM


def test_walk_json_path_dict_and_list() -> None:
    data = {"a": {"b": [10, {"c": "found"}]}}
    found, value = _walk_json_path(data, "a.b.1.c")
    assert found and value == "found"
    found, value = _walk_json_path(data, "a.b.0")
    assert found and value == 10
    found, _ = _walk_json_path(data, "a.missing")
    assert not found


def test_parse_llm_value_bare_text() -> None:
    assert _parse_llm_value("just text") == "just text"
    assert _parse_llm_value('```json\n{"k": 1}\n```') == {"k": 1}
    assert _parse_llm_value("") == ""


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
