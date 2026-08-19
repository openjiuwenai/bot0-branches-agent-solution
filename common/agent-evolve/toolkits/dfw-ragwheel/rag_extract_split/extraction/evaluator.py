#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from typing import AbstractSet, Any, Dict, List, Optional, Sequence, Tuple

from rag_extract_split.infrastructure.chroma_store import query_topk
from rag_extract_split.common.helpers import append_pretty_json_block, log_dir, now_ms, truncate
from rag_extract_split.config.settings import CONFIG


def evaluate_recall_detail(
    *,
    collection_name: str,
    badcases: Sequence[Dict[str, Any]],
    top_k: int,
    completed_answers: Optional[AbstractSet[str]],
    trace_context: Optional[Dict[str, Any]],
    badcase_query_embedding_cache: Optional[Dict[str, List[float]]] = None,
    query_batch_size: Optional[int] = None,
) -> Tuple[float, bool, Dict[str, Dict[str, Any]]]:
    done_set: AbstractSet[str] = frozenset(completed_answers or ())
    # 与 rag_extract_service.evaluate_recall_detail 一致（answer 列由上游归一化）
    qs = [str(b.get("query") or "").strip() for b in badcases]
    ans = [str(b.get("answer") or "").strip() for b in badcases]
    pairs = [(q, a) for q, a in zip(qs, ans) if q and a]
    if not pairs:
        return 0.0, False, {}

    query_pairs = [(q, a) for q, a in pairs if a not in done_set]
    if query_pairs:
        query_texts = [p[0] for p in query_pairs]
        q_embs: Optional[List[List[float]]] = None
        if badcase_query_embedding_cache is not None and all(q in badcase_query_embedding_cache for q in query_texts):
            q_embs = [list(badcase_query_embedding_cache[q]) for q in query_texts]
        results = query_topk(
            collection_name,
            query_texts,
            top_k,
            query_embeddings=q_embs,
            batch_size=query_batch_size,
        )
    else:
        results = []

    qi = 0
    hit_flags: List[bool] = []
    for q, a in pairs:
        if a in done_set:
            hit_flags.append(True)
            continue
        r = results[qi] if qi < len(results) else {}
        qi += 1
        metas = r.get("metadatas") or []
        ok = False
        for m in metas:
            if not isinstance(m, dict):
                continue
            ra = str(m.get("answer") or "").strip()
            if ra and ra == a:
                ok = True
                break
        hit_flags.append(ok)

    by_answer: Dict[str, Dict[str, Any]] = {}
    for (_, a), ok in zip(pairs, hit_flags):
        if a not in by_answer:
            by_answer[a] = {"hit": 0, "total": 0, "rate": 0.0}
        by_answer[a]["total"] += 1
        if ok:
            by_answer[a]["hit"] += 1
    for d in by_answer.values():
        t = int(d["total"] or 0)
        d["rate"] = (float(d["hit"]) / float(t)) if t else 0.0

    hit = sum(1 for x in hit_flags if x)
    rate = hit / float(len(pairs))
    all_ok = hit == len(pairs)

    # trace（每轮一次）
    if CONFIG.get("rag_extract", {}).get("recall_eval_trace_enabled", True) and trace_context:
        skip_indices = [i for i, (_, a) in enumerate(pairs) if a in done_set]
        qp_index_map: List[int] = [i for i, (_, a) in enumerate(pairs) if a not in done_set]
        request_items = []
        for slot, pi in enumerate(qp_index_map):
            qv, av = pairs[pi]
            request_items.append(
                {
                    "chroma_result_slot": slot,
                    "badcase_index": pi,
                    "query_text": truncate(qv, 600),
                    "expected_answer_label": truncate(av, 200),
                }
            )
        response_items = []
        for slot, pi in enumerate(qp_index_map):
            qv, av = pairs[pi]
            ok_i = hit_flags[pi]
            rrow = results[slot] if slot < len(results) else {}
            metas2 = rrow.get("metadatas") or []
            dists2 = rrow.get("distances") or []
            ids2 = rrow.get("ids") or []
            docs2 = rrow.get("documents") or []
            nk = max(len(metas2), len(dists2), len(ids2), len(docs2))
            nk = min(int(top_k), nk)
            ranked = []
            for j in range(nk):
                m2 = metas2[j] if j < len(metas2) else {}
                dist_v = dists2[j] if j < len(dists2) else None
                try:
                    dist_f = float(dist_v) if dist_v is not None else None
                except Exception:
                    dist_f = None
                m_ans = str((m2 or {}).get("answer") or "").strip()
                ranked.append(
                    {
                        "rank": j + 1,
                        "chroma_distance": dist_f,
                        "answer_matches_expected": bool(m_ans and m_ans == str(av).strip()),
                        "chunk_id": truncate(str(ids2[j] if j < len(ids2) else ""), 160),
                        "stored_document_query": truncate(str(docs2[j] if j < len(docs2) else ""), 400),
                        "metadata_answer": truncate(m_ans, 400),
                    }
                )
            response_items.append(
                {
                    "chroma_result_slot": slot,
                    "badcase_index": pi,
                    "query_text": truncate(qv, 600),
                    "expected_answer_label": truncate(av, 200),
                    "hit_in_topk": ok_i,
                    "top_k_retrieval": ranked,
                }
            )
        rec = {
            "kind": "rag_extract_chroma_recall_round",
            "meta": {
                "ts_ms": now_ms(),
                "task_id": trace_context.get("task_id"),
                "round_num": trace_context.get("round_num"),
                "collection": collection_name,
                "top_k": int(top_k),
            },
            "summary": {
                "badcase_rows_total": len(pairs),
                "skipped_completed_category_rows": len(skip_indices),
                "vector_batch_size": len(query_pairs),
                "recall_rate": round(rate, 6),
                "hit_count": int(hit),
                "all_ok": all_ok,
                "skipped_badcase_indexes_sample": skip_indices[:40],
            },
            "chroma_query_input": {
                "collection": collection_name,
                "n_results_top_k": int(top_k),
                "query_batch": request_items,
            },
            "chroma_query_output": {"per_query_retrieval": response_items},
        }
        fn = str(CONFIG.get("logging", {}).get("recall_trace_file") or "rag_extract_recall_trace.log")
        append_pretty_json_block(log_dir() / fn, rec)

    return rate, all_ok, by_answer

