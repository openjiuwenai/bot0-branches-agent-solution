from __future__ import annotations

import threading
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from backend.knowledge_qc.checkers.corpus_scope import normalize_similarity_hit
from backend.knowledge_qc.checkers.hit_filters import filter_hits_for_record
from backend.knowledge_qc.models import Issue, QARecord
from backend.knowledge_qc.services.embedder import Embedder
from backend.knowledge_qc.services.llm import LLMClient
from backend.knowledge_qc.services.vector_store import ChromaVectorStore


@dataclass
class CheckContext:
    rules: Dict[str, Any]
    embedder: Embedder
    vector_store: ChromaVectorStore
    llm: Optional[LLMClient] = None
    embedding_cache: Dict[str, List[float]] = field(default_factory=dict)
    similarity_hits_cache: Dict[str, List[Dict[str, Any]]] = field(default_factory=dict)
    similarity_hits_preloaded: bool = False
    llm_semantic_cache: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    llm_hit_cache: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    wordlist_overrides: Dict[str, List[str]] = field(default_factory=dict)
    skip_llm_semantic: bool = False
    cache_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    on_log: Optional[Callable[[str], None]] = None

    def get_embedding(self, question: str) -> List[float]:
        text = (question or "").strip()
        if not text:
            return self.embedder.embed_one("")
        if text not in self.embedding_cache:
            with self.cache_lock:
                if text not in self.embedding_cache:
                    self.embedding_cache[text] = self.embedder.embed_one(text)
        return self.embedding_cache[text]

    def get_similarity_hits(self, record: QARecord) -> List[Dict[str, Any]]:
        """重复/冲突检测用命中列表；预查完成后仅读缓存，不再访问向量库。"""
        if self.similarity_hits_preloaded:
            return list(self.similarity_hits_cache.get(record.record_id, []))
        if record.record_id in self.similarity_hits_cache:
            return self.similarity_hits_cache[record.record_id]

        top_k = self.rules.get("similarity", {}).get("top_k", 5)
        mode = self.rules.get("detection_mode", {})
        embedding = self.get_embedding(record.question)
        hits = self.vector_store.query_both(
            embedding,
            top_k,
            exclude_id=record.record_id,
            search_production=mode.get("production", True),
            search_staging=mode.get("batch", True),
        )
        hits = filter_hits_for_record(hits, record)
        hits = [normalize_similarity_hit(h) for h in hits]
        self.similarity_hits_cache[record.record_id] = hits
        return hits


class BaseChecker(ABC):
    dimension: str = ""

    @abstractmethod
    def check(self, record: QARecord, ctx: CheckContext) -> List[Issue]:
        pass
