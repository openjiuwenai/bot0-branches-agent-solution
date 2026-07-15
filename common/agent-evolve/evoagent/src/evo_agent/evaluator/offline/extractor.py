"""答案提取器 —— auto-degrade：regex → json_path → LLM → 空。

对每条预测原文，按确定性优先级尝试提取可与 gold 比较的值：

1. 若配置了 ``regex`` 且命中 → 取首个捕获组（无组则整段匹配）。
2. 仍未取到且配置了 ``json_path`` → 把原文解析为 JSON，按点分路径取值。
3. 仍未取到 → LLM 提取（``prompt`` + 预测原文）；LLM 异常或空 → ``""``。

LLM 输出尽量解析为 JSON（dict/list/标量），解析失败则取裸文本。最终值交给 scorer
的 per-case 指标 ``compute(extracted, gold)``——两侧各自 stringify，故标量与
结构化值均可比较。
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from typing import Any

from openjiuwen.core.foundation.llm import Model, UserMessage

logger = logging.getLogger(__name__)

__all__ = ["ExtractionConfig", "AnswerExtractor", "ExtractionResult"]

# 提取方式常量，集中管理避免散落字符串。
METHOD_REGEX = "regex"
METHOD_JSON_PATH = "json_path"
METHOD_LLM = "llm"
METHOD_EMPTY = "empty"


@dataclass(frozen=True)
class ExtractionResult:
    """单条预测的提取结果——由 pipeline 与 case_id/gold 组装成 MaterializedCase。"""

    extracted: Any
    method: str


@dataclass(frozen=True)
class ExtractionConfig:
    """提取配置——由路由层从 API 请求组装（含已构建的 ``Model``）。

    Attributes:
        regex: 可选正则；命中即确定性提取。
        json_path: 可选点分路径；原文解析为 JSON 后按路径取值。
        model: LLM 客户端；auto-degrade 回退与默认 LLM 提取均使用。
        prompt: LLM 提取 prompt；支持 ``{prediction}`` 占位符，否则追加原文。
    """

    regex: str | None
    json_path: str | None
    model: Model
    prompt: str


def _parse_llm_value(content: str) -> Any:
    """把 LLM 输出解析为 JSON 值（dict/list/标量），失败则返回裸文本。

    先剥离 ```` ```json ```` 代码块，再 ``json.loads`` 整段或首个 JSON 片段。
    """
    text = content.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)\s*```", text, re.DOTALL)
    if fence:
        text = fence.group(1).strip()
    if not text:
        return ""
    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        pass
    # 兜底：取首个 {...} 或 [...] 片段再试一次。
    for pattern in (r"\{.*\}", r"\[.*\]"):
        match = re.search(pattern, text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except (json.JSONDecodeError, ValueError):
                continue
    return text


def _walk_json_path(data: Any, path: str) -> tuple[bool, Any]:
    """按点分路径在已解析 JSON 上取值。

    支持 dict 键与 list 索引（如 ``a.b[0].c`` 中的 ``[0]`` 写成 ``a.b.0.c``）。
    返回 ``(found, value)``；任一段缺失即 ``(False, None)``。
    """
    current: Any = data
    for segment in path.split("."):
        segment = segment.strip()
        if segment == "":
            continue
        if isinstance(current, dict):
            if segment not in current:
                return False, None
            current = current[segment]
        elif isinstance(current, list):
            try:
                idx = int(segment)
            except ValueError:
                return False, None
            if idx < 0 or idx >= len(current):
                return False, None
            current = current[idx]
        else:
            return False, None
    return True, current


class AnswerExtractor:
    """auto-degrade 答案提取器。"""

    def __init__(self, config: ExtractionConfig) -> None:
        self._config = config

    async def extract(self, raw_prediction: Any) -> ExtractionResult:
        """提取一条预测的答案值与方式。

        Returns:
            ``(extracted, method)``。提取失败时 ``extracted = ""``、
            ``method = "empty"``。
        """
        raw_text = "" if raw_prediction is None else str(raw_prediction)

        # 1. regex
        if self._config.regex:
            match = re.search(self._config.regex, raw_text)
            if match:
                extracted = match.group(1) if match.groups() else match.group(0)
                return ExtractionResult(extracted=extracted, method=METHOD_REGEX)

        # 2. json_path
        if self._config.json_path:
            try:
                parsed = json.loads(raw_text)
            except (json.JSONDecodeError, ValueError):
                parsed = None
            if parsed is not None:
                found, value = _walk_json_path(parsed, self._config.json_path)
                if found and value not in (None, "", [], {}):
                    return ExtractionResult(extracted=value, method=METHOD_JSON_PATH)

        # 3. LLM 回退（也是无确定性配置时的默认路径）
        try:
            content = await self._invoke_llm(raw_text)
        except Exception as e:  # noqa: BLE001 — LLM 调用失败需降级为空，不阻断整批
            logger.warning("LLM extraction failed: %s", e)
            return ExtractionResult(extracted="", method=METHOD_EMPTY)

        extracted = _parse_llm_value(content)
        if extracted in (None, "", [], {}):
            return ExtractionResult(extracted="", method=METHOD_EMPTY)
        return ExtractionResult(extracted=extracted, method=METHOD_LLM)

    async def _invoke_llm(self, raw_text: str) -> str:
        """拼装 prompt 并调用 LLM，返回响应文本。"""
        prompt = self._config.prompt
        if "{prediction}" in prompt:
            prompt = prompt.replace("{prediction}", raw_text)
        else:
            prompt = f"{prompt}\n\n{raw_text}"
        response = await self._config.model.invoke([UserMessage(content=prompt)])
        return str(response.content)
