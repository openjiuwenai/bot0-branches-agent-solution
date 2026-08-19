#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional, Sequence

from rag_extract_split.common.helpers import restore_env, set_temp_env_for_proxy
from rag_extract_split.config.settings import CONFIG


def llm_config(name: Optional[str] = None) -> Dict[str, Any]:
    from rag_extract_split.config.llm_registry import get_llm_config

    cfg = get_llm_config(name)
    extra_headers = cfg.get("http_post_extra_headers") or {}
    if not isinstance(extra_headers, dict):
        extra_headers = {}
    extra_body = cfg.get("http_post_extra_body") or {}
    if not isinstance(extra_body, dict):
        extra_body = {}
    return {
        "request_mode": str(cfg.get("request_mode") or "openai").strip().lower(),
        "base_url": str(cfg.get("base_url") or "").rstrip("/"),
        "api_key": str(cfg.get("api_key") or ""),
        "model": str(cfg.get("model") or "gpt-4o-mini"),
        "timeout_sec": int(cfg.get("timeout_sec") or 60),
        "use_env_proxy": bool(cfg.get("use_env_proxy") or False),
        "https_proxy": str(cfg.get("https_proxy") or ""),
        "http_proxy": str(cfg.get("http_proxy") or ""),
        "no_proxy": str(cfg.get("no_proxy") or ""),
        "http_post_url": str(cfg.get("http_post_url") or "").strip(),
        "http_post_auth_header": str(cfg.get("http_post_auth_header") or "Authorization").strip(),
        "http_post_auth_scheme": str(cfg.get("http_post_auth_scheme") or "Bearer").strip(),
        "http_post_extra_headers": dict(extra_headers),
        "http_post_extra_body": dict(extra_body),
        "http_post_content_path": str(cfg.get("http_post_content_path") or "choices.0.message.content").strip(),
        "http_post_usage_path": str(cfg.get("http_post_usage_path") or "usage").strip(),
    }


def build_llm_messages(*, target_answer: str, samples: Sequence[str], count: int) -> List[Dict[str, str]]:
    system = (
        "你是 RAG 相似问生成器。根据输入的用户原话 sample_queries。归纳总结出有代表性的问题"
        "输出恰好 count 条 {q,a}：每条 q 为与原文不完全相同、语义多样的相似用户问法，便于向量检索；"
        "每条 a 必须与 target_answer 字符串完全一致。"
        "禁止语义重复或仅替换虚词；不要输出 markdown，只输出 JSON。"
    )
    user = {
        "task": "paraphrase_queries_one_answer",
        "target_answer": target_answer,
        "sample_queries": list(samples),
        "count": int(count),
        "output_schema": {"qa_pairs": [{"q": "相似问", "a": "等于 target_answer"}]},
        "constraints": [
            "顶层仅含 qa_pairs 数组",
            f"【最高优先级】qa_pairs 长度count必须严格等于 {int(count)}",
            "所有 a 必须等于 target_answer",
            "各 q 互不重复，能代表 sample_queries中的语义",
        ],
    }
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": json.dumps(user, ensure_ascii=False)},
    ]


def build_llm_trace_base(
    *,
    task_id: str,
    round_num: int,
    sub_call: int,
    target_answer: str,
    model: str,
    base_url: str,
    messages: Sequence[Dict[str, Any]],
    extra_body: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "ts": datetime.now(timezone.utc).isoformat(),
        "event": "rag_extract_llm_category",
        "task_id": task_id or None,
        "round_num": int(round_num or 0),
        "sub_call": int(sub_call),
        "target_answer": target_answer,
        "model": model,
        "base_url": base_url,
        "input": {"temperature": 0.2, "stream": False, "extra_body": extra_body, "messages": list(messages)},
    }


def extract_completion_usage(completion: Any) -> Any:
    usage = None
    u_obj = getattr(completion, "usage", None)
    if u_obj is None:
        return usage
    try:
        usage = u_obj.model_dump()
    except Exception:
        try:
            usage = dict(u_obj)
        except Exception:
            usage = str(u_obj)
    return usage


def extract_by_path(data: Any, path: str) -> Any:
    if not path:
        return data
    cur = data
    for token in [p for p in str(path).split(".") if p]:
        if isinstance(cur, list):
            try:
                idx = int(token)
            except Exception:
                return None
            if idx < 0 or idx >= len(cur):
                return None
            cur = cur[idx]
            continue
        if isinstance(cur, dict):
            if token not in cur:
                return None
            cur = cur[token]
            continue
        return None
    return cur


def normalize_llm_content(raw_content: str) -> str:
    content = (raw_content or "").strip()
    if content.startswith("```"):
        content = content.strip("`")
        content = content.replace("json", "", 1).strip()
    return content


def parse_llm_qa_pairs(content: str) -> List[Dict[str, Any]]:
    obj = json.loads(content)
    qa_pairs = obj.get("qa_pairs") if isinstance(obj, dict) else None
    if not isinstance(qa_pairs, list):
        raise ValueError("missing qa_pairs")
    return qa_pairs


def to_output_pairs(qa_pairs: Sequence[Dict[str, Any]], target_answer: str, count: int) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for qa in qa_pairs:
        if not isinstance(qa, dict):
            continue
        q = str(qa.get("q") or "").strip()
        out.append({"q": q or "（空问题）", "a": target_answer})
    while len(out) < int(count):
        out.append({"q": f"相似问补充{len(out)+1}", "a": target_answer})
    return out[: int(count)]


def run_llm_completion_http_post(
    *,
    cfg: Dict[str, Any],
    model: str,
    messages: Sequence[Dict[str, str]],
    extra_body: Dict[str, Any],
) -> Dict[str, Any]:
    import httpx

    url = str(cfg.get("http_post_url") or cfg.get("base_url") or "").strip()
    if not url:
        raise ValueError("rag_llm.http_post_url/base_url 未配置")
    api_key = str(cfg.get("api_key") or "").strip()
    headers: Dict[str, str] = {"Content-Type": "application/json"}
    headers.update({str(k): str(v) for k, v in dict(cfg.get("http_post_extra_headers") or {}).items()})
    if api_key:
        auth_header = str(cfg.get("http_post_auth_header") or "Authorization").strip() or "Authorization"
        auth_scheme = str(cfg.get("http_post_auth_scheme") or "Bearer").strip()
        headers[auth_header] = f"{auth_scheme} {api_key}".strip() if auth_scheme else api_key

    # 知识质检 HTTP LLM 专有协议：{question, enableHistory, sessionId, userId}
    if cfg.get("knowledge_qc_http"):
        body = _build_knowledge_qc_http_body(cfg, messages)
    else:
        body: Dict[str, Any] = {
            "model": model,
            "messages": list(messages),
            "temperature": 0.2,
            "stream": False,
        }
        body.update(dict(extra_body or {}))
        body.update(dict(cfg.get("http_post_extra_body") or {}))

    old = set_temp_env_for_proxy(cfg["use_env_proxy"], cfg["https_proxy"], cfg["http_proxy"], cfg["no_proxy"])
    try:
        with httpx.Client(timeout=cfg["timeout_sec"], trust_env=cfg["use_env_proxy"]) as client:
            resp = client.post(url, json=body, headers=headers)
            resp.raise_for_status()
            data = resp.json()
    finally:
        restore_env(old)

    content_path = str(cfg.get("http_post_content_path") or "choices.0.message.content")
    usage_path = str(cfg.get("http_post_usage_path") or "usage")
    raw_content = extract_by_path(data, content_path)
    usage = extract_by_path(data, usage_path)
    return {"raw_content": str(raw_content or ""), "usage": usage, "raw_response": data}


def _build_knowledge_qc_http_body(cfg: Dict[str, Any], messages: Sequence[Dict[str, str]]) -> Dict[str, Any]:
    """构造知识质检 HTTP LLM 协议请求体。"""
    question = ""
    for msg in reversed(messages):
        if msg.get("role") == "user":
            question = str(msg.get("content") or "").strip()
            break
    if not question:
        question = str(messages[-1].get("content") or "").strip() if messages else ""

    extra_body = dict(cfg.get("http_post_extra_body") or {})
    body = {
        "question": question,
        "enableHistory": cfg.get("kqc_enable_history", False),
        "sessionId": str(cfg.get("kqc_session_id") or "0"),
        "userId": str(cfg.get("kqc_user_id") or "0"),
    }
    # 允许 http_post_extra_body 中的同名字段覆盖默认值
    for key in ("enableHistory", "sessionId", "userId"):
        if key in extra_body:
            body[key] = extra_body[key]
    return body


def run_llm_completion(
    *,
    cfg: Dict[str, Any],
    model: str,
    messages: Sequence[Dict[str, str]],
    extra_body: Dict[str, Any],
) -> Any:
    mode = str(cfg.get("request_mode") or "openai").strip().lower()
    if mode == "http_post":
        return run_llm_completion_http_post(cfg=cfg, model=model, messages=messages, extra_body=extra_body)

    from openai import OpenAI
    import httpx

    old = set_temp_env_for_proxy(cfg["use_env_proxy"], cfg["https_proxy"], cfg["http_proxy"], cfg["no_proxy"])
    try:
        http_client = httpx.Client(timeout=cfg["timeout_sec"], trust_env=cfg["use_env_proxy"])
        client = OpenAI(api_key=cfg["api_key"], base_url=cfg["base_url"], http_client=http_client)
        return client.chat.completions.create(
            model=model,
            temperature=0.2,
            messages=list(messages),
            timeout=cfg["timeout_sec"],
            stream=False,
            extra_body=extra_body,
        )
    finally:
        restore_env(old)


def generate_qa_pairs_llm_one_answer(
    *,
    target_answer: str,
    sample_queries: Sequence[str],
    rule_text: str,
    rule_label: str,
    count: int,
    existing_qas: Sequence[Dict[str, Any]],
    task_id: str,
    round_num: int,
    sub_call: int,
    append_trace: Callable[[Dict[str, Any]], None],
    llm_config_name: Optional[str] = None,
) -> List[Dict[str, Any]]:
    cfg = llm_config(llm_config_name)
    if not cfg["base_url"] or not cfg["api_key"]:
        raise ValueError("rag_llm base_url/api_key 未配置")

    samples = list(sample_queries)[:80]
    _ = (rule_text, rule_label, existing_qas)
    messages = build_llm_messages(target_answer=target_answer, samples=samples, count=count)
    extra_body = {"enable_thinking": False}
    trace_base = build_llm_trace_base(
        task_id=task_id,
        round_num=round_num,
        sub_call=sub_call,
        target_answer=target_answer,
        model=str(cfg["model"]),
        base_url=str(cfg["base_url"]),
        messages=messages,
        extra_body=extra_body,
    )

    try:
        completion = run_llm_completion(cfg=cfg, model=str(cfg["model"]), messages=messages, extra_body=extra_body)
    except Exception as e:
        append_trace({**trace_base, "output": None, "parse_error": None, "error": str(e)})
        raise

    if isinstance(completion, dict):
        usage = completion.get("usage")
        raw_content = str(completion.get("raw_content") or "").strip()
        raw_response = completion.get("raw_response")
    else:
        usage = extract_completion_usage(completion)
        raw_content = (completion.choices[0].message.content or "").strip()
        raw_response = None

    if not raw_content:
        append_trace(
            {
                **trace_base,
                "output": {"raw_content": "", "usage": usage, "raw_response": raw_response},
                "parse_error": None,
                "error": "empty",
            }
        )
        raise RuntimeError("模型返回为空")

    content = normalize_llm_content(raw_content)
    try:
        qa_pairs = parse_llm_qa_pairs(content)
    except Exception as e:
        append_trace(
            {
                **trace_base,
                "output": {"raw_content": raw_content, "usage": usage, "raw_response": raw_response},
                "parse_error": str(e),
                "error": None,
            }
        )
        if "missing qa_pairs" in str(e):
            raise RuntimeError("模型返回格式不正确（缺少 qa_pairs 数组）") from e
        raise RuntimeError(f"模型返回非合法 JSON: {e}") from e

    append_trace(
        {
            **trace_base,
            "output": {
                "raw_content": raw_content,
                "parsed_qa_pairs": qa_pairs,
                "usage": usage,
                "raw_response": raw_response,
            },
            "parse_error": None,
            "error": None,
        }
    )
    return to_output_pairs(qa_pairs, target_answer, count)
