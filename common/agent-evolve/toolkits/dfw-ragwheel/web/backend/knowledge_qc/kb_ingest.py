from __future__ import annotations

from typing import Callable, List, Optional, Union

from backend.knowledge_qc.models import IntentRecord, QARecord
from backend.knowledge_qc.services.chroma_batch import chroma_upsert_batch_size
from backend.knowledge_qc.services.embedder import Embedder
from backend.knowledge_qc.services.vector_store import ChromaVectorStore

Record = Union[QARecord, IntentRecord]
ProgressCallback = Callable[[str, int, int], None]


def _notify(
    on_progress: Optional[ProgressCallback], phase: str, current: int, total: int
) -> None:
    if on_progress:
        on_progress(phase, current, total)


def embed_texts_with_progress(
    embedder: Embedder,
    texts: List[str],
    embedding_batch_size: int,
    on_progress: Optional[ProgressCallback] = None,
) -> List[List[float]]:
    total = len(texts)
    if total == 0:
        return []
    _notify(on_progress, "embedding", 0, total)
    vectors: List[List[float]] = []
    bs = max(1, embedding_batch_size)
    for start in range(0, total, bs):
        chunk = texts[start:start + bs]
        vectors.extend(embedder.embed(chunk))
        current = min(start + len(chunk), total)
        _notify(on_progress, "embedding", current, total)
    return vectors


def ingest_questions(
    store: ChromaVectorStore,
    embedder: Embedder,
    records: List[QARecord],
    settings: dict,
    on_progress: Optional[ProgressCallback] = None,
) -> int:
    total = len(records)
    if total == 0:
        return 0
    texts = [r.question for r in records]
    emb_bs = int(settings.get("embedding_batch_size", 32))
    embeddings = embed_texts_with_progress(embedder, texts, emb_bs, on_progress)

    chroma_bs = chroma_upsert_batch_size(settings)
    _notify(on_progress, "chroma", 0, total)
    for start in range(0, total, chroma_bs):
        end = min(start + chroma_bs, total)
        store.upsert_records(
            store.production,
            records[start:end],
            embeddings[start:end],
            "ingest",
        )
        _notify(on_progress, "chroma", end, total)
    return total


def ingest_intents(
    store: ChromaVectorStore,
    embedder: Embedder,
    records: List[IntentRecord],
    settings: dict,
    on_progress: Optional[ProgressCallback] = None,
) -> int:
    total = len(records)
    if total == 0:
        return 0
    texts = [r.intent_description for r in records]
    emb_bs = int(settings.get("embedding_batch_size", 32))
    embeddings = embed_texts_with_progress(embedder, texts, emb_bs, on_progress)

    chroma_bs = chroma_upsert_batch_size(settings)
    _notify(on_progress, "chroma", 0, total)
    for start in range(0, total, chroma_bs):
        end = min(start + chroma_bs, total)
        store.upsert_intent_records(
            store.intent_production,
            records[start:end],
            embeddings[start:end],
            "ingest",
        )
        _notify(on_progress, "chroma", end, total)
    return total


def progress_label(phase: str, current: int, total: int) -> str:
    if phase == "embedding":
        return f"Embedding：已完成 {current}/总计 {total}"
    if phase == "chroma":
        return f"写入向量库：已完成 {current}/总计 {total}"
    return f"已完成 {current}/总计 {total}"
