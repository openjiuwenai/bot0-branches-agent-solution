from __future__ import annotations

from typing import Dict, Iterable, List

from backend.knowledge_qc.services.embedder import Embedder


def unique_nonempty_texts(texts: Iterable[str]) -> List[str]:
    seen: Dict[str, None] = {}
    for raw in texts:
        s = (raw or "").strip()
        if s:
            seen.setdefault(s, None)
    return list(seen.keys())


def preload_embedding_cache(
    embedder: Embedder, texts: Iterable[str]
) -> Dict[str, List[float]]:
    """整表去重后批量 Embedding，供质检逐条检索时复用。"""
    unique = unique_nonempty_texts(texts)
    cache: Dict[str, List[float]] = {}
    if not unique:
        return cache
    vectors = embedder.embed(unique)
    for text, vector in zip(unique, vectors):
        cache[text] = vector
    return cache
