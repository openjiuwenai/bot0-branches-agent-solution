#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Tuple

from rag_extract_split.common.helpers import append_jsonl, append_pretty_json_block, log_dir
from rag_extract_split.generation.cluster import (
    empty_cluster_meta,
    generate_qa_pairs_cluster_one_answer,
    generate_qa_pairs_fallback,
)
from rag_extract_split.generation.llm import generate_qa_pairs_llm_one_answer
from rag_extract_split.config.settings import CONFIG


def llm_trace_enabled() -> bool:
    return bool(CONFIG.get("rag_extract", {}).get("llm_trace_enabled", True))


def append_llm_trace(record: Dict[str, Any]) -> None:
    if not llm_trace_enabled():
        return
    fn = str(CONFIG.get("logging", {}).get("llm_trace_file") or "rag_extract_llm_trace.log")
    append_jsonl(log_dir() / fn, record)


def append_cluster_round_trace(record: Dict[str, Any]) -> None:
    fn = str(CONFIG.get("logging", {}).get("cluster_trace_file") or "rag_extract_cluster_trace.log")
    append_pretty_json_block(log_dir() / fn, record)


def generate_qa_pairs_for_answer_category(
    *,
    answer: str,
    sample_queries: Sequence[str],
    rule_text: str,
    rule_label: str,
    count: int,
    existing_qas: Sequence[Dict[str, Any]],
    task_id: str,
    round_num: int,
    sub_call: int,
    completion_mode: str,
    query_embedding_cache: Optional[Dict[str, List[float]]] = None,
    llm_config_name: Optional[str] = None,
) -> Tuple[List[Dict[str, Any]], str, Dict[str, Any]]:
    """
    返回 (qa_pairs, mode, extra_meta)。
    - llm 模式：优先 llm，失败 fallback
    - cluster 模式：纯聚类，不调用 llm
    """
    n = int(count or 0)
    ans = str(answer or "").strip()
    if n <= 0 or not ans:
        return [], "none", {}
    mode = str(completion_mode or "llm").strip().lower()
    if mode == "cluster":
        try:
            pairs, cluster_meta = generate_qa_pairs_cluster_one_answer(
                answer=ans,
                sample_queries=list(sample_queries),
                existing_qas=existing_qas,
                max_count=n,
                query_embedding_cache=query_embedding_cache,
            )
            return pairs, "cluster", cluster_meta
        except Exception:
            # 仍保持“纯非 LLM”约束：聚类失败时仅退化为模板，不调模型。
            fb = generate_qa_pairs_fallback(ans, sample_queries, n)
            return fb, "cluster_fallback", {
                **empty_cluster_meta(
                    representative_count=len(fb),
                    representative_questions=[str(x.get("q") or "") for x in fb],
                ),
                "candidate_count": len([str(q).strip() for q in sample_queries if str(q).strip()]),
            }
    try:
        pairs = generate_qa_pairs_llm_one_answer(
            target_answer=ans,
            sample_queries=list(sample_queries),
            rule_text=rule_text,
            rule_label=rule_label,
            count=n,
            existing_qas=existing_qas,
            task_id=task_id,
            round_num=round_num,
            sub_call=sub_call,
            append_trace=append_llm_trace,
            llm_config_name=llm_config_name,
        )
        return pairs, "llm", {}
    except Exception:
        return generate_qa_pairs_fallback(ans, sample_queries, n), "fallback", {}

