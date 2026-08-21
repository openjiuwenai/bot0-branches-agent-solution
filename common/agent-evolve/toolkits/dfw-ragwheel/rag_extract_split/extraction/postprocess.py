#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from typing import Any, Dict, List, Sequence

from rag_extract_split.infrastructure.chroma_store import query_topk


def attach_high_similarity_hits(
    *,
    collection: str,
    qa_pairs: Sequence[Dict[str, Any]],
    top_k: int,
    distance_threshold: float,
    query_batch_size: int = 64,
) -> List[Dict[str, Any]]:
    pairs = [dict(p) for p in qa_pairs]
    queries = [str(p.get("q") or p.get("query") or "").strip() for p in pairs]
    if not pairs or top_k <= 0:
        return pairs

    valid_idx = [i for i, q in enumerate(queries) if q]
    if not valid_idx:
        return pairs

    q_batch = [queries[i] for i in valid_idx]
    try:
        res_all = query_topk(collection, q_batch, top_k, batch_size=max(1, int(query_batch_size)))
    except Exception:
        return pairs

    for local_i, res in enumerate(res_all):
        i = valid_idx[local_i]
        docs = res.get("documents") or []
        metas = res.get("metadatas") or []
        dists = res.get("distances") or []
        candidates: List[str] = []
        top1_q = ""
        top1_a = ""
        top1_d = ""
        for j in range(max(len(docs), len(metas), len(dists))):
            d = dists[j] if j < len(dists) else None
            try:
                d_f = float(d) if d is not None else None
            except Exception:
                d_f = None
            if d_f is None or d_f > distance_threshold:
                continue
            cand_q = str(docs[j] if j < len(docs) else "").strip()
            cand_a = str(
                (metas[j] or {}).get("answer")
                if j < len(metas) and isinstance(metas[j], dict)
                else ""
            ).strip()
            if not cand_q and not cand_a:
                continue
            if not top1_q:
                top1_q = cand_q
                top1_a = cand_a
                top1_d = f"{d_f:.6f}"
            candidates.append(f"q={cand_q} | a={cand_a} | dist={d_f:.6f}")

        pairs[i]["sim_top1_q"] = top1_q
        pairs[i]["sim_top1_a"] = top1_a
        pairs[i]["sim_top1_dist"] = top1_d
        pairs[i]["sim_hits"] = " || ".join(candidates)
        pairs[i]["sim_hit_count"] = len(candidates)

    return pairs
