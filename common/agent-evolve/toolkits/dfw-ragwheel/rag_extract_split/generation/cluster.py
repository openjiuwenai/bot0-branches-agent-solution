#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Set, Tuple

from rag_extract_split.infrastructure.embedding import embed_texts


def empty_cluster_meta(*, representative_count: int = 0, representative_questions: Optional[List[str]] = None) -> Dict[str, Any]:
    return {
        "candidate_count": 0,
        "cluster_count": 0,
        "noise_dropped_count": 0,
        "representative_count": int(representative_count),
        "representative_questions": list(representative_questions or []),
    }


def generate_qa_pairs_fallback(answer: str, sample_queries: Sequence[str], count: int) -> List[Dict[str, Any]]:
    qs = [str(q).strip() for q in sample_queries if str(q).strip()]
    tpl = ["{M}怎么办理？", "如何查询{M}？", "手机上{m}入口在哪？", "{M}要注意什么？", "请问{m}流程？"]
    short = (answer or "")[:24] or "该业务"
    out: List[Dict[str, Any]] = []
    for i in range(int(count)):
        if qs:
            q = qs[i % len(qs)]
        else:
            t = tpl[i % len(tpl)]
            q = t.replace("{M}", answer).replace("{m}", short)
        out.append({"q": q, "a": answer})
    return out


def generate_qa_pairs_cluster_one_answer(
    *,
    answer: str,
    sample_queries: Sequence[str],
    existing_qas: Sequence[Dict[str, Any]],
    max_count: int,
    query_embedding_cache: Optional[Dict[str, List[float]]] = None,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    from sklearn.cluster import KMeans
    from sklearn.metrics.pairwise import pairwise_distances_argmin_min
    import numpy as np

    def build_cluster_meta(
        *,
        candidate_count: int,
        cluster_count: int,
        noise_dropped_count: int,
        representative_questions: Sequence[str],
    ) -> Dict[str, Any]:
        return {
            "candidate_count": int(candidate_count),
            "cluster_count": int(cluster_count),
            "noise_dropped_count": int(noise_dropped_count),
            "representative_count": len(list(representative_questions)),
            "representative_questions": list(representative_questions),
        }

    def load_candidate_embeddings(candidates: Sequence[str]) -> List[List[float]]:
        if query_embedding_cache is not None and all(q in query_embedding_cache for q in candidates):
            return [list(query_embedding_cache[q]) for q in candidates]
        vecs = embed_texts(candidates)
        if query_embedding_cache is not None:
            for q, v in zip(candidates, vecs):
                query_embedding_cache.setdefault(q, list(v))
        return vecs

    ans = str(answer or "").strip()
    if not ans or max_count <= 0:
        return [], empty_cluster_meta()

    raw = [str(q or "").strip() for q in sample_queries if str(q or "").strip()]
    if not raw:
        return [], empty_cluster_meta()

    existed = {str(qa.get("q") or "").strip() for qa in existing_qas if str(qa.get("q") or "").strip()}
    candidates = []
    seen: Set[str] = set()
    for q in raw:
        if q in existed or q in seen:
            continue
        seen.add(q)
        candidates.append(q)
    if not candidates:
        return [], empty_cluster_meta()

    vecs = load_candidate_embeddings(candidates)
    emb = np.asarray(vecs, dtype=np.float32)
    k = max(1, int(max_count))
    if emb.ndim != 2 or emb.shape[0] != len(candidates):
        reps = [{"q": q, "a": ans} for q in candidates[:k]]
        if len(reps) < k:
            reps.extend(generate_qa_pairs_fallback(ans, candidates, k - len(reps)))
        reps = reps[:k]
        return reps, build_cluster_meta(
            candidate_count=len(candidates),
            cluster_count=0,
            noise_dropped_count=0,
            representative_questions=[str(x.get("q") or "") for x in reps],
        )

    cluster_k = min(k, len(candidates))
    model = KMeans(n_clusters=cluster_k, n_init=10, random_state=42)
    labels = model.fit_predict(emb)
    centers = np.asarray(model.cluster_centers_, dtype=np.float32)

    reps: List[str] = []
    for lab in range(cluster_k):
        idxs = np.where(labels == lab)[0]
        if len(idxs) == 0:
            continue
        c_emb = emb[idxs]
        centroid = centers[lab : lab + 1]
        nearest_local = int(pairwise_distances_argmin_min(centroid, c_emb)[0][0])
        reps.append(candidates[int(idxs[nearest_local])])

    out: List[Dict[str, Any]] = []
    used: Set[str] = set()
    # candidates 与 vecs 一一对应：用于 cluster 模式下入库复用 embedding，避免二次 embed。
    q_to_vec: Dict[str, List[float]] = {q: list(v) for q, v in zip(candidates, vecs)}
    for q in reps:
        if not q or q in used:
            continue
        used.add(q)
        out.append({"q": q, "a": ans, "_embedding": q_to_vec.get(q)})
        if len(out) >= k:
            break
    if len(out) < k:
        for qa in generate_qa_pairs_fallback(ans, candidates, k):
            out.append({"q": str(qa.get("q") or ""), "a": ans})
            if len(out) >= k:
                break

    return out, build_cluster_meta(
        candidate_count=len(candidates),
        cluster_count=cluster_k,
        noise_dropped_count=0,
        representative_questions=[str(x.get("q") or "") for x in out],
    )
