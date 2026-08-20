"""Chroma 检索命中解析与多路结果合并。"""

from __future__ import annotations

from typing import Any, Dict, List, Optional


def parse_row_index(meta: dict) -> Optional[int]:
    row_index = meta.get("row_index")
    if row_index is not None and row_index != "":
        try:
            return int(row_index)
        except (TypeError, ValueError):
            return None
    return None


def hit_from_parts(
    doc_id: str,
    document: str,
    meta: dict,
    distance: float,
    corpus_scope: str,
) -> Dict[str, Any]:
    similarity = 1.0 - distance
    meta = meta or {}
    return {
        "id": doc_id,
        "document": document,
        "intent_name": meta.get("intent_name") or meta.get("answer_menu", ""),
        "intent_description": meta.get("intent_description")
        or meta.get("description", ""),
        "source": meta.get("source", ""),
        "row_index": parse_row_index(meta),
        "corpus_scope": corpus_scope,
        "similarity": round(similarity, 4),
        "distance": distance,
    }


def hits_from_query_result(
    result: dict,
    exclude_id: Optional[str],
    corpus_scope: str,
) -> List[Dict[str, Any]]:
    hits: List[Dict[str, Any]] = []
    if not result.get("ids") or not result["ids"][0]:
        return hits

    for i, doc_id in enumerate(result["ids"][0]):
        if exclude_id and doc_id == exclude_id:
            continue
        distance = result["distances"][0][i] if result.get("distances") else 0.0
        meta = (result["metadatas"][0][i] if result.get("metadatas") else {}) or {}
        doc = result["documents"][0][i] if result.get("documents") else ""
        hits.append(hit_from_parts(doc_id, doc, meta, distance, corpus_scope))
    return hits


def hits_from_batch_query_result(
    result: dict,
    exclude_ids: Optional[List[Optional[str]]],
    corpus_scope: str,
) -> List[List[Dict[str, Any]]]:
    """解析 Chroma 批量 query 结果，与 query_embeddings 顺序一一对应。"""
    ids_batch = result.get("ids") or []
    batch_hits: List[List[Dict[str, Any]]] = []
    for q_idx, id_list in enumerate(ids_batch):
        exclude_id = None
        if exclude_ids and q_idx < len(exclude_ids):
            exclude_id = exclude_ids[q_idx]
        single = {
            "ids": [id_list or []],
            "distances": (
                [result["distances"][q_idx]]
                if result.get("distances") and q_idx < len(result["distances"])
                else None
            ),
            "metadatas": (
                [result["metadatas"][q_idx]]
                if result.get("metadatas") and q_idx < len(result["metadatas"])
                else None
            ),
            "documents": (
                [result["documents"][q_idx]]
                if result.get("documents") and q_idx < len(result["documents"])
                else None
            ),
        }
        batch_hits.append(hits_from_query_result(single, exclude_id, corpus_scope))
    return batch_hits


def merge_hits(
    parts: List[List[Dict[str, Any]]],
    top_k: int = 0,
) -> List[Dict[str, Any]]:
    """合并多路检索结果；每路已各自限制 top_k，此处不再做全局截断。"""
    merged: List[Dict[str, Any]] = []
    for group in parts:
        merged.extend(group)
    merged.sort(key=lambda x: x["similarity"], reverse=True)
    if top_k > 0:
        return merged[:top_k]
    return merged
