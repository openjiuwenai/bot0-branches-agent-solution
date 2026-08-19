from __future__ import annotations

from typing import Any, Dict, List, Optional, Protocol

import requests

from backend.knowledge_qc.services.llm_prompts import (
    DEFAULT_RESULT,
    DUP_CONFLICT_BATCH_DEFAULT,
    DUP_CONFLICT_DEFAULT,
    INTENT_DESC_DEFAULT,
    build_dup_conflict_http_question,
    build_dup_conflict_openai_messages,
    build_http_question,
    build_intent_desc_http_question,
    build_intent_desc_openai_messages,
    build_openai_messages,
    format_dup_conflict_batch_prompt,
    format_intent_desc_prompt,
    format_semantic_prompt,
    normalize_dup_conflict_batch_result,
    normalize_intent_desc_result,
    normalize_result,
    parse_json_object,
)


class LLMRequestError(RuntimeError):
    """单条质检 LLM 调用失败，由流水线捕获并标记为质检异常。"""


class LLMClient(Protocol):
    def judge_semantic_quality(
        self, question: str, intent_name: str, intent_description: str = ""
    ) -> Dict[str, Any]:
        ...

    def judge_dup_conflict_hits(
        self,
        question: str,
        intent_name: str,
        hits: List[Dict[str, Any]],
        relation: str,
    ) -> Dict[str, Any]:
        ...

    def judge_intent_description_duplicate(
        self,
        intent_name: str,
        description: str,
        hits: List[Dict[str, Any]],
        row_index: int = 0,
    ) -> Dict[str, Any]:
        ...


class OpenAILLM:
    """OpenAI 兼容 LLM（按需导入 openai）。"""

    def __init__(
        self,
        api_key: str,
        base_url: str,
        model: str,
        timeout: float = 120.0,
        max_tokens: int = 256,
    ):
        if not api_key:
            raise ValueError("未配置 LLM_API_KEY，请在 .env 中设置")
        from openai import OpenAI

        self._client = OpenAI(
            api_key=api_key, base_url=base_url, timeout=timeout, max_retries=1
        )
        self._model = model
        self._max_tokens = max_tokens

    def judge_semantic_quality(
        self,
        question: str,
        intent_name: str,
        intent_description: str = "",
    ) -> Dict[str, Any]:
        prompt = format_semantic_prompt(
            question, intent_name, intent_description
        )
        raw = self._chat_openai(prompt)
        return normalize_result(parse_json_object(raw, default=dict(DEFAULT_RESULT)))

    def judge_intent_description_duplicate(
        self,
        intent_name: str,
        description: str,
        hits: List[Dict[str, Any]],
        row_index: int = 0,
    ) -> Dict[str, Any]:
        prompt = format_intent_desc_prompt(
            intent_name, description, hits, row_index
        )
        raw = self._chat_raw(
            build_intent_desc_openai_messages(prompt),
            max_tokens=max(self._max_tokens, 512),
        )
        return normalize_intent_desc_result(
            parse_json_object(raw, default=dict(INTENT_DESC_DEFAULT))
        )

    def judge_dup_conflict_hits(
        self,
        question: str,
        intent_name: str,
        hits: List[Dict[str, Any]],
        relation: str,
    ) -> Dict[str, Any]:
        if not hits:
            return {"judgments": []}
        prompt = format_dup_conflict_batch_prompt(
            question, intent_name, hits, relation
        )
        tokens = max(self._max_tokens, min(512, 96 * len(hits) + 128))
        raw = self._chat_raw(
            build_dup_conflict_openai_messages(prompt, relation),
            tokens,
        )
        return normalize_dup_conflict_batch_result(
            parse_json_object(raw, default=dict(DUP_CONFLICT_BATCH_DEFAULT)),
            len(hits),
            relation,
        )

    def _chat_openai(self, user_prompt: str) -> str:
        return self._chat_raw(build_openai_messages(user_prompt), self._max_tokens)

    def _chat_raw(self, messages: List[dict], max_tokens: int) -> str:
        resp = self._client.chat.completions.create(
            model=self._model,
            messages=messages,
            temperature=0,
            max_tokens=max_tokens,
        )
        return resp.choices[0].message.content or ""


class HttpLLM:
    """自定义 HTTP 大模型对话服务。"""

    def __init__(
        self,
        url: str,
        token: str,
        session_id: str = "0",
        user_id: str = "0",
        enable_history: str = "false",
        timeout: float = 120.0,
    ):
        if not url:
            raise ValueError("未配置 HTTP_LLM_URL，请在 .env 中设置")
        if not token:
            raise ValueError("未配置 HTTP_LLM_TOKEN，请在 .env 中设置")
        self._url = url
        self._token = token
        self._session_id = session_id
        self._user_id = user_id
        self._enable_history = enable_history
        self._timeout = timeout

    def judge_semantic_quality(
        self,
        question: str,
        intent_name: str,
        intent_description: str = "",
    ) -> Dict[str, Any]:
        prompt = format_semantic_prompt(
            question, intent_name, intent_description
        )
        raw = self._chat_http(build_http_question(prompt))
        return normalize_result(parse_json_object(raw, default=dict(DEFAULT_RESULT)))

    def judge_intent_description_duplicate(
        self,
        intent_name: str,
        description: str,
        hits: List[Dict[str, Any]],
        row_index: int = 0,
    ) -> Dict[str, Any]:
        prompt = format_intent_desc_prompt(
            intent_name, description, hits, row_index
        )
        raw = self._chat_http(build_intent_desc_http_question(prompt))
        return normalize_intent_desc_result(
            parse_json_object(raw, default=dict(INTENT_DESC_DEFAULT))
        )

    def judge_dup_conflict_hits(
        self,
        question: str,
        intent_name: str,
        hits: List[Dict[str, Any]],
        relation: str,
    ) -> Dict[str, Any]:
        if not hits:
            return {"judgments": []}
        prompt = format_dup_conflict_batch_prompt(
            question, intent_name, hits, relation
        )
        raw = self._chat_http(build_dup_conflict_http_question(prompt, relation))
        return normalize_dup_conflict_batch_result(
            parse_json_object(raw, default=dict(DUP_CONFLICT_BATCH_DEFAULT)),
            len(hits),
            relation,
        )

    def _chat_http(self, user_prompt: str) -> str:
        headers = {
            "Content-Type": "application/json",
            "token": self._token,
        }
        payload = {
            "question": user_prompt,
            "enableHistory": self._enable_history,
            "sessionId": self._session_id,
            "userId": self._user_id,
        }
        try:
            response = requests.post(
                self._url,
                headers=headers,
                json=payload,
                timeout=self._timeout,
            )
            response.raise_for_status()
            result = response.json()
            return result["result"]["finalAnswer"]
        except Exception as e:
            raise RuntimeError(f"LLM HTTP 请求失败: {e}") from e


def create_llm(settings: dict, max_tokens: Optional[int] = None) -> LLMClient:
    mode = (settings.get("llm_mode") or "openai").lower()
    timeout = float(settings.get("api_timeout", 120.0))
    tokens = max_tokens if max_tokens is not None else int(
        settings.get("llm_max_tokens", 256)
    )

    if mode == "http":
        return HttpLLM(
            url=settings.get("http_llm_url", ""),
            token=settings.get("http_llm_token", ""),
            session_id=settings.get("http_llm_session_id", "0"),
            user_id=settings.get("http_llm_user_id", "0"),
            enable_history=settings.get("http_llm_enable_history", "false"),
            timeout=timeout,
        )

    return OpenAILLM(
        api_key=settings.get("llm_api_key", ""),
        base_url=settings.get("llm_base_url", ""),
        model=settings.get("llm_model", ""),
        timeout=timeout,
        max_tokens=tokens,
    )


# 兼容旧代码引用
OnlineLLM = OpenAILLM
