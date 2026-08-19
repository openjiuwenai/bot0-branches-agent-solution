#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""External service adapters (embedding, vector store)."""

from rag_extract_split.infrastructure.embedding import (
    embed_texts,
    embed_stats_snapshot,
    reset_embed_stats,
    embed_texts_hashing,
)
from rag_extract_split.infrastructure.embedding_http import embed_texts_http_post
from rag_extract_split.infrastructure.chroma_store import (
    chroma_hnsw_space,
    clear_collection_fully,
    delete_ids,
    drop_collection,
    get_by_ids,
    get_chroma_client,
    get_or_create_collection,
    query_topk,
    reset_chroma_client,
    upsert_cases,
)

__all__ = [
    "embed_texts",
    "embed_stats_snapshot",
    "reset_embed_stats",
    "embed_texts_hashing",
    "embed_texts_http_post",
    "chroma_hnsw_space",
    "clear_collection_fully",
    "delete_ids",
    "drop_collection",
    "get_by_ids",
    "get_chroma_client",
    "get_or_create_collection",
    "query_topk",
    "reset_chroma_client",
    "upsert_cases",
]
