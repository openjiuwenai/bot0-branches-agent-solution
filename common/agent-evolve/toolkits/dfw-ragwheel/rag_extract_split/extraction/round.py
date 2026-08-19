#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import uuid
from typing import Any, Dict, List, Sequence, Set, Tuple

from rag_extract_split.extraction.allocator import allocate_round_category_counts
from rag_extract_split.infrastructure.chroma_store import upsert_cases
from rag_extract_split.common.helpers import now_ms
from rag_extract_split.generation.orchestrator import append_cluster_round_trace, generate_qa_pairs_for_answer_category
from rag_extract_split.config.models import RAGCase


def prepare_round_allocation(
    *,
    ordered_answers: Sequence[str],
    frozen_answers_done: Set[str],
    completion_mode: str,
    round_num: int,
    count: int,
    badcases: Sequence[Dict[str, Any]],
    accumulated_qas_len: int,
    last_recall_detail: Dict[str, Dict[str, Any]],
) -> Tuple[List[str], Dict[str, int], int, str]:
    active = [a for a in ordered_answers if a not in frozen_answers_done]
    alloc, budget, alloc_mode = allocate_round_category_counts(
        round_num=round_num,
        count=count,
        active=active,
        badcases=badcases,
        accumulated_qas_len=accumulated_qas_len,
        last_recall_detail=last_recall_detail,
    )
    if completion_mode == "cluster":
        return active, alloc, budget, f"{alloc_mode}（cluster）"
    return active, alloc, budget, alloc_mode


def generate_and_upsert_round(
    *,
    active: Sequence[str],
    alloc: Dict[str, int],
    answer_to_queries: Dict[str, List[str]],
    accumulated_qas: Sequence[Dict[str, Any]],
    rule_text: str,
    rule_label: str,
    task_id: str,
    round_num: int,
    completion_mode: str,
    bad_query_embedding_cache: Dict[str, List[float]],
    collection: str,
    run_tag: str,
    write_batch_size: int,
    llm_config_name: Optional[str] = None,
) -> Dict[str, Any]:
    round_qas_by_answer: Dict[str, List[Dict[str, Any]]] = {a: [] for a in active}
    round_chroma_ids: List[str] = []
    sub = 0
    gen_total = 0
    llm_cat = 0
    fb_cat = 0
    cluster_cat = 0
    cluster_round_detail: Dict[str, Dict[str, Any]] = {}

    for ans in active:
        n = max(0, int(alloc.get(ans, 0) or 0))
        if n <= 0:
            continue
        queries = list(answer_to_queries.get(ans, []))
        existing = [qa for qa in accumulated_qas if str(qa.get("a") or "").strip() == ans]
        pairs, gen_mode, gen_meta = generate_qa_pairs_for_answer_category(
            answer=ans,
            sample_queries=queries,
            rule_text=rule_text,
            rule_label=rule_label,
            count=n,
            existing_qas=existing,
            task_id=task_id,
            round_num=round_num,
            sub_call=sub,
            completion_mode=completion_mode,
            query_embedding_cache=bad_query_embedding_cache,
            llm_config_name=llm_config_name,
        )
        if gen_mode == "llm":
            llm_cat += 1
        elif gen_mode == "fallback":
            fb_cat += 1
        elif gen_mode in {"cluster", "cluster_fallback"}:
            cluster_cat += 1
            cluster_round_detail[ans] = {
                "candidate_count": int(gen_meta.get("candidate_count") or 0),
                "cluster_count": int(gen_meta.get("cluster_count") or 0),
                "noise_dropped_count": int(gen_meta.get("noise_dropped_count") or 0),
                "representative_count": int(gen_meta.get("representative_count") or 0),
                "representative_questions": list(gen_meta.get("representative_questions") or []),
            }
        sub += 1
        round_qas_by_answer[ans] = pairs
        gen_total += len(pairs)
        cases_batch: List[RAGCase] = []
        case_embeddings: List[List[float]] = []
        embeddings_ok = True
        for i, qa in enumerate(pairs):
            q = str(qa.get("q") or "").strip()
            a = str(qa.get("a") or "").strip()
            if not q:
                continue
            cid = f"extract:{run_tag}:{ans[:24]}:{i}:{sub}:{uuid.uuid4().hex[:6]}"
            round_chroma_ids.append(cid)
            cases_batch.append(
                RAGCase(
                    case_id=cid,
                    query=q,
                    answer=a,
                    metadata={
                        "source": "rag_extract",
                        "task_id": task_id,
                        "round": round_num,
                        "run_tag": run_tag,
                    },
                )
            )
            v = qa.get("_embedding")
            if v is None:
                embeddings_ok = False
            else:
                try:
                    case_embeddings.append(list(v))
                except Exception:
                    embeddings_ok = False
        if cases_batch:
            if embeddings_ok and len(case_embeddings) == len(cases_batch):
                upsert_cases(
                    collection,
                    cases_batch,
                    batch_size=max(1, int(write_batch_size)),
                    embeddings=case_embeddings,
                )
            else:
                upsert_cases(collection, cases_batch, batch_size=max(1, int(write_batch_size)))

    return {
        "round_qas_by_answer": round_qas_by_answer,
        "round_chroma_ids": round_chroma_ids,
        "gen_total": gen_total,
        "llm_cat": llm_cat,
        "fb_cat": fb_cat,
        "cluster_cat": cluster_cat,
        "cluster_round_detail": cluster_round_detail,
    }


def append_cluster_trace_if_needed(
    *,
    completion_mode: str,
    active: Sequence[str],
    cluster_round_detail: Dict[str, Dict[str, Any]],
    task_id: str,
    round_num: int,
    collection: str,
    gen_total: int,
    recall_rate: float,
    all_ok: bool,
) -> None:
    if completion_mode != "cluster":
        return
    per_answer_rows = []
    for a in active:
        d = cluster_round_detail.get(a) or {}
        per_answer_rows.append(
            {
                "answer": a,
                "candidate_count": int(d.get("candidate_count") or 0),
                "cluster_count": int(d.get("cluster_count") or 0),
                "noise_dropped_count": int(d.get("noise_dropped_count") or 0),
                "representative_count": int(d.get("representative_count") or 0),
                "representative_questions": list(d.get("representative_questions") or []),
            }
        )
    append_cluster_round_trace(
        {
            "kind": "rag_extract_cluster_round",
            "meta": {
                "ts_ms": now_ms(),
                "task_id": task_id,
                "round_num": int(round_num),
                "collection": collection,
                "completion_mode": completion_mode,
            },
            "summary": {
                "active_answer_categories": len(active),
                "generated_pairs_total": int(gen_total),
                "recall_rate": float(recall_rate),
                "all_ok": bool(all_ok),
            },
            "per_answer": per_answer_rows,
        }
    )
