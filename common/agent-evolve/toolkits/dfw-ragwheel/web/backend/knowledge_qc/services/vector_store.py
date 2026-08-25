from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, List, Optional

from chromadb.api.models.Collection import Collection

from backend.knowledge_qc.models import IntentRecord, QARecord
from backend.knowledge_qc.services.chroma_client import open_chroma_client
from backend.knowledge_qc.services.chroma_hits import (
    hits_from_batch_query_result,
    hits_from_query_result,
    merge_hits,
)


@dataclass
class VectorQueryOpts:
    top_k: int = 5
    exclude_id: Optional[str] = None
    corpus_scope: Optional[str] = None
    where: Optional[Dict[str, Any]] = None


@dataclass
class IntentBothQueryOpts:
    exclude_id: Optional[str] = None
    search_production: bool = True
    search_staging: bool = True
    intent_filter: Optional[Dict[str, Any]] = None


class ChromaVectorStore:
    """Chroma：相似问库 + 意图描述库（各含 production / staging）。"""

    def __init__(
        self,
        persist_dir: str,
        production_name: str,
        staging_name: str,
        intent_production_name: str = "kb_intent_production",
        intent_staging_name: str = "kb_intent_staging",
        init_timeout: float = 15.0,
        upsert_batch_size: int = 100,
    ):
        self._client = open_chroma_client(persist_dir, init_timeout)
        self._upsert_batch_size = max(1, int(upsert_batch_size))
        self._production = self._get_or_create(production_name)
        self._staging = self._get_or_create(staging_name)
        self._intent_production = self._get_or_create(intent_production_name)
        self._intent_staging = self._get_or_create(intent_staging_name)

    def _get_or_create(self, name: str) -> Collection:
        return self._client.get_or_create_collection(
            name=name,
            metadata={"hnsw:space": "cosine"},
        )

    @property
    def production(self) -> Collection:
        return self._production

    @property
    def staging(self) -> Collection:
        return self._staging

    @property
    def intent_production(self) -> Collection:
        return self._intent_production

    @property
    def intent_staging(self) -> Collection:
        return self._intent_staging

    def clear_staging(self) -> None:
        if self._staging.count() > 0:
            self._staging.delete(where={"record_id": {"$ne": ""}})

    def clear_intent_staging(self) -> None:
        if self._intent_staging.count() > 0:
            self._intent_staging.delete(where={"record_id": {"$ne": ""}})

    def clear_production(self) -> None:
        if self._production.count() > 0:
            self._production.delete(where={"record_id": {"$ne": ""}})

    def clear_intent_production(self) -> None:
        if self._intent_production.count() > 0:
            self._intent_production.delete(where={"record_id": {"$ne": ""}})

    def _chunked_upsert(
        self,
        collection: Collection,
        ids: List[str],
        embeddings: List[List[float]],
        documents: List[str],
        metadatas: List[dict],
    ) -> None:
        n = len(ids)
        bs = self._upsert_batch_size
        for start in range(0, n, bs):
            end = start + bs
            collection.upsert(
                ids=ids[start:end],
                embeddings=embeddings[start:end],
                documents=documents[start:end],
                metadatas=metadatas[start:end],
            )

    def upsert_records(
        self,
        collection: Collection,
        records: List[QARecord],
        embeddings: List[List[float]],
        source: str = "batch",
    ) -> None:
        if not records:
            return
        ids = [r.record_id for r in records]
        documents = [r.question for r in records]
        metadatas = [
            {
                "intent_name": r.intent_name,
                "intent_description": r.intent_description,
                "source": source,
                "row_index": r.row_index,
            }
            for r in records
        ]
        self._chunked_upsert(collection, ids, embeddings, documents, metadatas)

    def upsert_intent_records(
        self,
        collection: Collection,
        records: List[IntentRecord],
        embeddings: List[List[float]],
        source: str = "batch",
    ) -> None:
        if not records:
            return
        ids = [r.record_id for r in records]
        documents = [r.intent_description for r in records]
        metadatas = [
            {
                "intent_name": r.intent_name,
                "intent_description": r.intent_description,
                "source": source,
                "row_index": r.row_index,
            }
            for r in records
        ]
        self._chunked_upsert(collection, ids, embeddings, documents, metadatas)

    @staticmethod
    def query(
        collection: Collection,
        embedding: List[float],
        opts: Optional[VectorQueryOpts] = None,
    ) -> List[Dict[str, Any]]:
        query_opts = opts or VectorQueryOpts()
        if collection.count() == 0:
            return []
        result = collection.query(
            query_embeddings=[embedding],
            n_results=min(query_opts.top_k, collection.count()),
            include=["documents", "metadatas", "distances"],
            where=query_opts.where,
        )
        return hits_from_query_result(
            result, query_opts.exclude_id, query_opts.corpus_scope or ""
        )

    @staticmethod
    def merge_hits(
        parts: List[List[Dict[str, Any]]],
        top_k: int = 0,
    ) -> List[Dict[str, Any]]:
        return merge_hits(parts, top_k)

    @staticmethod
    def query_batch(
        collection: Collection,
        embeddings: List[List[float]],
        top_k: int,
        exclude_ids: Optional[List[Optional[str]]] = None,
        corpus_scope: Optional[str] = None,
    ) -> List[List[Dict[str, Any]]]:
        n = len(embeddings)
        out: List[List[Dict[str, Any]]] = [[] for _ in range(n)]
        if n == 0 or collection.count() == 0:
            return out

        valid_idx = [i for i, emb in enumerate(embeddings) if emb]
        if not valid_idx:
            return out

        valid_embeddings = [embeddings[i] for i in valid_idx]
        valid_excludes = (
            [exclude_ids[i] if exclude_ids else None for i in valid_idx]
            if exclude_ids
            else None
        )
        result = collection.query(
            query_embeddings=valid_embeddings,
            n_results=min(top_k, collection.count()),
            include=["documents", "metadatas", "distances"],
        )
        valid_hits = hits_from_batch_query_result(
            result, valid_excludes, corpus_scope or ""
        )
        for idx, hits in zip(valid_idx, valid_hits):
            out[idx] = hits
        return out

    def query_both_batch(
        self,
        embeddings: List[List[float]],
        top_k: int,
        exclude_ids: Optional[List[Optional[str]]] = None,
        search_production: bool = True,
        search_staging: bool = True,
    ) -> List[List[Dict[str, Any]]]:
        n = len(embeddings)
        merged: List[List[Dict[str, Any]]] = [[] for _ in range(n)]
        if n == 0:
            return merged
        if search_production:
            prod_parts = self.query_batch(
                self._production,
                embeddings,
                top_k,
                exclude_ids,
                corpus_scope="production",
            )
            for i in range(n):
                merged[i] = self.merge_hits([merged[i], prod_parts[i]])
        if search_staging:
            staging_parts = self.query_batch(
                self._staging,
                embeddings,
                top_k,
                exclude_ids,
                corpus_scope="batch",
            )
            for i in range(n):
                merged[i] = self.merge_hits([merged[i], staging_parts[i]])
        return merged

    def query_both(
        self,
        embedding: List[float],
        top_k: int,
        exclude_id: Optional[str] = None,
        search_production: bool = True,
        search_staging: bool = True,
    ) -> List[Dict[str, Any]]:
        parts: List[List[Dict[str, Any]]] = []
        if search_production:
            parts.append(
                self.query(
                    self._production,
                    embedding,
                    VectorQueryOpts(
                        top_k=top_k,
                        exclude_id=exclude_id,
                        corpus_scope="production",
                    ),
                )
            )
        if search_staging:
            parts.append(
                self.query(
                    self._staging,
                    embedding,
                    VectorQueryOpts(
                        top_k=top_k,
                        exclude_id=exclude_id,
                        corpus_scope="batch",
                    ),
                )
            )
        return self.merge_hits(parts)

    def query_intent_both(
        self,
        embedding: List[float],
        top_k: int,
        opts: Optional[IntentBothQueryOpts] = None,
    ) -> List[Dict[str, Any]]:
        query_opts = opts or IntentBothQueryOpts()
        parts: List[List[Dict[str, Any]]] = []
        where = None
        intent_filter = query_opts.intent_filter
        if intent_filter and intent_filter.get("mode") and intent_filter.get("intents"):
            intents = [str(x).strip() for x in intent_filter["intents"] if str(x).strip()]
            if intents:
                if intent_filter["mode"] == "include":
                    where = {"intent_name": {"$in": intents}}
                elif intent_filter["mode"] == "exclude":
                    where = {"intent_name": {"$nin": intents}}
        if query_opts.search_production:
            parts.append(
                self.query(
                    self._intent_production,
                    embedding,
                    VectorQueryOpts(
                        top_k=top_k,
                        exclude_id=query_opts.exclude_id,
                        corpus_scope="production",
                        where=where,
                    ),
                )
            )
        if query_opts.search_staging:
            parts.append(
                self.query(
                    self._intent_staging,
                    embedding,
                    VectorQueryOpts(
                        top_k=top_k,
                        exclude_id=query_opts.exclude_id,
                        corpus_scope="batch",
                        where=None,
                    ),
                )
            )
        return self.merge_hits(parts)
