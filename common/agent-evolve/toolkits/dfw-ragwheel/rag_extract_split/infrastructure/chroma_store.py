#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from rag_extract_split.infrastructure.embedding import embed_texts
from rag_extract_split.config.models import RAGCase
from rag_extract_split.config.settings import CONFIG
from rag_extract_split.config.embedding_manager import get_active_embedding_config

logger = logging.getLogger(__name__)

CHROMA_CLIENT = None
CHROMA_CLIENT_KEY = None


def _project_root() -> Path:
    """通过包位置推断项目根目录（rag_extract_split/infrastructure/chroma_store.py -> 项目根）。"""
    return Path(__file__).resolve().parent.parent.parent


def _resolve_persist_dir(persist_dir: str) -> str:
    """将相对 persist_dir 解析为项目根目录下的绝对路径，避免受当前工作目录影响。"""
    p = Path(persist_dir)
    if not p.is_absolute():
        p = _project_root() / p
    return str(p.resolve())


def _chunked(items: Sequence[Any], size: int) -> Iterable[Sequence[Any]]:
    bs = max(1, int(size or len(items) or 1))
    for i in range(0, len(items), bs):
        yield items[i:i + bs]


def chroma_hnsw_space() -> str:
    """读取 CONFIG chroma.hnsw_space，返回 Chroma 支持的 hnsw:space 值。"""
    cfg = CONFIG.get("chroma", {})
    space = str(cfg.get("hnsw_space") or "l2").strip().lower()
    allowed = {"l2", "cosine", "ip"}
    if space not in allowed:
        return "l2"
    return space


def chroma_collection_metadata() -> Dict[str, str]:
    """返回创建 collection 时写入的元数据，包含 hnsw 空间与当前激活的 embedding 配置。"""
    meta: Dict[str, str] = {"hnsw:space": chroma_hnsw_space()}
    try:
        cfg = get_active_embedding_config()
        name = cfg.get("embedding_config_name") or cfg.get("name")
        if not name:
            # 尝试从管理器读取当前激活名称
            from rag_extract_split.config.embedding_manager import get_manager
            name = get_manager().get_active_name()
        if name:
            meta["embedding_config"] = str(name)
        model = cfg.get("embedding_model_name") or cfg.get("model")
        if model:
            meta["embedding_model"] = str(model)
    except Exception:
        logger.debug("failed to attach embedding metadata to chroma collection", exc_info=True)
    return meta


def get_chroma_client():
    global CHROMA_CLIENT, CHROMA_CLIENT_KEY
    cfg = CONFIG.get("chroma", {})
    host = str(cfg.get("host") or "").strip()
    port = int(cfg.get("port") or 8000)
    ssl = bool(cfg.get("ssl") or False)
    persist_dir = _resolve_persist_dir(
        str(os.environ.get("CHROMA_DB_PATH") or cfg.get("persist_dir") or "data/chromadb")
    )
    key = f"http:{host}:{port}:{'s' if ssl else ''}" if host else f"local:{persist_dir}"
    if CHROMA_CLIENT is not None and CHROMA_CLIENT_KEY == key:
        return CHROMA_CLIENT
    import chromadb

    if host:
        CHROMA_CLIENT = chromadb.HttpClient(host=host, port=port, ssl=ssl)
    else:
        p = Path(persist_dir)
        p.mkdir(parents=True, exist_ok=True)
        CHROMA_CLIENT = chromadb.PersistentClient(path=str(p))
    CHROMA_CLIENT_KEY = key
    return CHROMA_CLIENT


def get_or_create_collection(collection_name: str):
    client = get_chroma_client()
    name = str(collection_name).strip()
    meta = chroma_collection_metadata()
    return client.get_or_create_collection(name=name, metadata=meta)


def clear_collection_fully(collection_name: str) -> None:
    name = str(collection_name).strip()
    client = get_chroma_client()
    try:
        client.delete_collection(name)
    except Exception:
        logger.debug("delete_collection failed; will recreate collection", exc_info=True)
    client.get_or_create_collection(name=name, metadata=chroma_collection_metadata())


def reset_chroma_client() -> None:
    """清空全局缓存的 ChromaDB client，强制下次重新连接。"""
    global CHROMA_CLIENT, CHROMA_CLIENT_KEY
    CHROMA_CLIENT = None
    CHROMA_CLIENT_KEY = None


def _chroma_persist_dir() -> str:
    """获取当前 ChromaDB 持久化目录（解析为绝对路径）。"""
    cfg = CONFIG.get("chroma", {})
    return _resolve_persist_dir(
        str(os.environ.get("CHROMA_DB_PATH") or cfg.get("persist_dir") or "data/chromadb")
    )


def drop_collection(collection_name: str) -> None:
    """彻底删除 collection（不再重建）。兼容 CLI 和 Flask 长驻进程。"""
    import logging
    logger = logging.getLogger(__name__)

    name = str(collection_name).strip()
    persist_dir = _chroma_persist_dir()
    logger.warning("[drop_collection] target=%r persist_dir=%r", name, persist_dir)

    # 关键：强制重置全局缓存，避免 Flask 长驻进程持有旧 client
    reset_chroma_client()

    # 使用全新的 client 执行删除，确保读到最新磁盘状态
    import chromadb
    client = chromadb.PersistentClient(path=persist_dir)
    logger.warning("[drop_collection] fresh_client created, type=%s", type(client).__name__)

    try:
        before = [c.name for c in client.list_collections()]
        logger.warning("[drop_collection] before=%s", before)
    except Exception as exc:
        logger.warning("[drop_collection] list before failed: %s", exc)

    try:
        client.delete_collection(name)
        logger.warning("[drop_collection] delete_collection succeeded")
    except Exception as exc:
        if "does not exist" in str(exc).lower():
            logger.warning("[drop_collection] collection does not exist: %s", exc)
        else:
            logger.error("[drop_collection] delete_collection failed: %s", exc)
            raise

    try:
        after = [c.name for c in client.list_collections()]
        logger.warning("[drop_collection] after=%s", after)
    except Exception as exc:
        logger.warning("[drop_collection] list after failed: %s", exc)

    # 再次重置全局缓存，强制后续 list/query 重新连接
    reset_chroma_client()


def delete_ids(collection_name: str, ids: Sequence[str], *, batch_size: Optional[int] = None) -> int:
    id_list = [str(i) for i in (ids or []) if str(i).strip()]
    if not id_list:
        return 0
    col = get_or_create_collection(collection_name)
    deleted = 0
    for part in _chunked(id_list, int(batch_size or len(id_list))):
        col.delete(ids=list(part))
        deleted += len(part)
    return deleted


def get_by_ids(
    collection_name: str,
    ids: Sequence[str],
    *,
    include: Optional[Sequence[str]] = None,
    batch_size: Optional[int] = None,
) -> Dict[str, List[Any]]:
    id_list = [str(i) for i in (ids or []) if str(i).strip()]
    out: Dict[str, List[Any]] = {"ids": [], "documents": [], "metadatas": [], "embeddings": [], "distances": []}
    if not id_list:
        return out
    col = get_or_create_collection(collection_name)
    req_include = list(include or ["documents", "metadatas"])
    for part in _chunked(id_list, int(batch_size or len(id_list))):
        res = col.get(ids=list(part), include=req_include)
        for k in out.keys():
            out[k].extend(list(res.get(k) or []))
    return out


def upsert_cases(
    collection_name: str,
    cases: Sequence[RAGCase],
    *,
    batch_size: Optional[int] = None,
    embeddings: Optional[Sequence[Sequence[float]]] = None,
) -> int:
    col = get_or_create_collection(collection_name)
    ids: List[str] = []
    docs: List[str] = []
    metas: List[Dict[str, Any]] = []
    vecs_in: List[List[float]] = []
    for c in cases:
        q = (c.query or "").strip()
        a = (c.answer or "").strip()
        if not q:
            continue
        ids.append(str(c.case_id))
        docs.append(q)
        m = dict(c.metadata or {})
        m["query"] = q
        m["answer"] = a
        metas.append(m)
    if not ids:
        return 0

    # 若传入 embeddings，则尝试与过滤后的 docs 对齐复用，避免重复 embedding。
    # 不匹配时自动回退到 embed_texts 现算，保证兼容旧调用方。
    if embeddings is not None:
        try:
            vecs_in = [list(v) for v in embeddings]
        except Exception:
            vecs_in = []
        if len(vecs_in) != len(docs):
            vecs_in = []

    written = 0
    bs = int(batch_size or len(ids))
    idx_chunks = list(_chunked(ids, bs))
    doc_chunks = list(_chunked(docs, bs))
    meta_chunks = list(_chunked(metas, bs))
    vec_chunks: Optional[List[Sequence[Sequence[float]]]] = None
    if vecs_in:
        vec_chunks = list(_chunked(vecs_in, bs))

    for i, (idx_part, doc_part, meta_part) in enumerate(zip(idx_chunks, doc_chunks, meta_chunks)):
        if vec_chunks is not None and i < len(vec_chunks):
            vec_part = vec_chunks[i]
        else:
            vec_part = embed_texts(doc_part)
        col.upsert(ids=list(idx_part), documents=list(doc_part), metadatas=list(meta_part), embeddings=vec_part)
        written += len(idx_part)
    return written


def query_topk(
    collection_name: str,
    query_texts: Sequence[str],
    top_k: int,
    *,
    query_embeddings: Optional[Sequence[Sequence[float]]] = None,
    batch_size: Optional[int] = None,
) -> List[Dict[str, Any]]:
    if not query_texts:
        return []
    k = max(1, min(20, int(top_k or 5)))
    col = get_or_create_collection(collection_name)
    if query_embeddings is None:
        vecs = embed_texts([str(t or "") for t in query_texts])
    else:
        vecs = [list(v) for v in query_embeddings]
        if len(vecs) != len(query_texts):
            raise ValueError(f"query_embeddings 数量不匹配: {len(vecs)} != {len(query_texts)}")
    out: List[Dict[str, Any]] = []
    bs = max(1, int(batch_size or len(query_texts)))
    for start in range(0, len(query_texts), bs):
        end = min(start + bs, len(query_texts))
        q_part = query_texts[start:end]
        v_part = vecs[start:end]
        res = col.query(query_embeddings=v_part, n_results=k, include=["metadatas", "documents", "distances"])
        ids_ll = res.get("ids") or []
        metas_ll = res.get("metadatas") or []
        docs_ll = res.get("documents") or []
        dist_ll = res.get("distances") or []
        for i, query in enumerate(q_part):
            out.append(
                {
                    "query": query,
                    "ids": ids_ll[i] if i < len(ids_ll) else [],
                    "metadatas": metas_ll[i] if i < len(metas_ll) else [],
                    "documents": docs_ll[i] if i < len(docs_ll) else [],
                    "distances": dist_ll[i] if i < len(dist_ll) else [],
                }
            )
    return out

