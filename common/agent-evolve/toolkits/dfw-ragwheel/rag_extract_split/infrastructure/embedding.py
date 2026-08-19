#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import os
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

from rag_extract_split.infrastructure.embedding_http import embed_texts_http_post
from rag_extract_split.common.helpers import restore_env, set_temp_env_for_proxy
from rag_extract_split.config.settings import CONFIG
from rag_extract_split.config.embedding_manager import get_active_embedding_config

# 本轮/本阶段 embedding 调用统计（用于控制台输出）
EMBED_STATS: Dict[str, int] = {
    "local_batches": 0,
    "local_texts": 0,
    "remote_batches": 0,
    "remote_texts": 0,
    "hashing_full_fallback": 0,  # 整批回退 hashing（含未配置或 API 失败）
    "hashing_vectors": 0,  # hashing 产生的向量条数
}


def reset_embed_stats() -> None:
    EMBED_STATS.clear()
    EMBED_STATS.update(
        {
            "local_batches": 0,
            "local_texts": 0,
            "remote_batches": 0,
            "remote_texts": 0,
            "hashing_full_fallback": 0,
            "hashing_vectors": 0,
        }
    )


def embed_stats_snapshot() -> Dict[str, int]:
    return dict(EMBED_STATS)


def embed_texts_hashing(texts: Sequence[str], dim: int = 384) -> List[List[float]]:
    from sklearn.feature_extraction.text import HashingVectorizer
    import numpy as np

    vec = HashingVectorizer(n_features=dim, alternate_sign=False, norm=None, lowercase=False)
    x = vec.transform([t or "" for t in texts]).astype(np.float32)
    dense = x.toarray()
    norms = np.linalg.norm(dense, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    dense = dense / norms
    return dense.tolist()


LOCAL_EMBED_FUNC = None
LOCAL_EMBED_FUNC_KEY = None


def _vector_to_list(v: object) -> List[float]:
    """兼容 ndarray/list/tuple 等 embedding 返回类型。"""
    if hasattr(v, "tolist"):
        out = v.tolist()  # type: ignore[attr-defined]
    else:
        out = list(v)  # type: ignore[arg-type]
    return [float(x) for x in out]


def embedding_failure_hint(exc: BaseException) -> str:
    msg = str(exc).lower()
    if "qwen3" in msg or "model type" in msg or "does not recognize this architecture" in msg:
        return (
            "  提示：当前 Transformers 版本不支持该目录中的 Qwen3 类模型。"
            "可任选其一：① 升级 `transformers`/`sentence-transformers` 到支持 Qwen3 的版本；"
            "② 将 CONFIG rag_embedding.mode 改为 remote 使用兼容的远程 embedding；"
            "③ 换用已支持的本地模型目录（如 BGE 等）。"
        )
    return ""


def get_local_embed_func(model_dir: str, *, normalize_embeddings: bool):
    """
    懒加载本地 embedding 函数（按 tools/test.py 的方式：Chroma 内置 SentenceTransformerEmbeddingFunction）。
    model_dir：本地模型目录，如 D:\\bge-small-zh-v1.5
    """
    global LOCAL_EMBED_FUNC, LOCAL_EMBED_FUNC_KEY
    key = f"{str(model_dir or '').strip()}|norm={bool(normalize_embeddings)}"
    if not model_dir:
        return None
    if LOCAL_EMBED_FUNC is not None and LOCAL_EMBED_FUNC_KEY == key:
        return LOCAL_EMBED_FUNC

    # 必须在导入任何 transformers/sentence_transformers 前设置离线模式（参照 tools/test.py）
    os.environ.setdefault("HF_OFFLINE", "1")
    os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    try:
        from chromadb.utils import embedding_functions
    except Exception as e:
        raise RuntimeError("无法导入 chromadb.utils.embedding_functions，请确认已安装 chromadb") from e

    LOCAL_EMBED_FUNC = embedding_functions.SentenceTransformerEmbeddingFunction(
        model_name=str(model_dir),
        normalize_embeddings=bool(normalize_embeddings),
    )
    LOCAL_EMBED_FUNC_KEY = key
    return LOCAL_EMBED_FUNC


def embed_texts(texts: Sequence[str]) -> List[List[float]]:
    # 优先从独立 Embedding 配置管理器读取当前激活配置；失败则回退 settings.py
    try:
        cfg = get_active_embedding_config()
    except Exception:
        cfg = CONFIG.get("rag_embedding", {})
    if not cfg:
        cfg = CONFIG.get("rag_embedding", {})
    mode = str(cfg.get("mode") or "local").strip().lower()
    request_mode = str(cfg.get("request_mode") or "openai").strip().lower()
    local_dir = str(cfg.get("local_model_dir") or r"D:\bge-small-zh-v1.5").strip()
    normalize = bool(cfg.get("normalize_embeddings") if "normalize_embeddings" in cfg else True)

    base_url = str(cfg.get("embedding_base_url") or "").rstrip("/")
    model = str(cfg.get("embedding_model_name") or "").strip()
    api_key = str(cfg.get("embedding_api_key") or "")
    timeout_sec = int(cfg.get("timeout_sec") or 120)
    bs = int(cfg.get("batch_size") or 32)
    use_env_proxy = bool(cfg.get("use_env_proxy") or False)
    https_proxy = str(cfg.get("https_proxy") or "")
    http_proxy = str(cfg.get("http_proxy") or "")
    no_proxy = str(cfg.get("no_proxy") or "")

    norm = [str(t or "") for t in texts]
    if not norm:
        return []

    # 1) 本地模型优先（用户指定）
    if mode in {"local", "sentence_transformers", "st"}:
        if local_dir and Path(local_dir).exists():
            try:
                embf = get_local_embed_func(local_dir, normalize_embeddings=normalize)
                out_local: List[List[float]] = []
                for i in range(0, len(norm), max(1, bs)):
                    chunk = norm[i : i + max(1, bs)]
                    vecs = embf(chunk)
                    part = [_vector_to_list(v) for v in vecs]
                    out_local.extend(part)
                    EMBED_STATS["local_batches"] += 1
                    EMBED_STATS["local_texts"] += len(chunk)
                # 维度一致性校验
                if out_local:
                    d0 = len(out_local[0])
                    for j, row in enumerate(out_local):
                        if len(row) != d0:
                            raise RuntimeError(f"embedding 向量维度不一致 row0={d0} row{j}={len(row)}")
                return out_local
            except Exception as e:
                # 用户显式要求本地 embedding：失败应直接抛错，避免静默回退导致“看似能跑但召回异常”
                hint = embedding_failure_hint(e)
                raise RuntimeError(f"本地 embedding 失败（model_dir={local_dir}）: {e}\n{hint}".rstrip()) from e
        raise RuntimeError(f"本地 embedding 模型目录不存在或不可用: {local_dir}")

    # 2) 远程 embedding（openai SDK 或 http_post）
    if request_mode == "http_post":
        def _on_remote_batch(n: int) -> None:
            EMBED_STATS["remote_batches"] += 1
            EMBED_STATS["remote_texts"] += int(n)

        try:
            out_http = embed_texts_http_post(
                norm,
                cfg,
                vector_to_list=_vector_to_list,
                on_batch=_on_remote_batch,
            )
            if out_http:
                d0 = len(out_http[0])
                for j, row in enumerate(out_http):
                    if len(row) != d0:
                        raise RuntimeError(f"embedding 向量维度不一致 row0={d0} row{j}={len(row)}")
            return out_http
        except Exception:
            vecs = embed_texts_hashing(norm, dim=384)
            EMBED_STATS["hashing_full_fallback"] += 1
            EMBED_STATS["hashing_vectors"] += len(vecs)
            return vecs

    if not base_url or not model:
        vecs = embed_texts_hashing(norm, dim=384)
        EMBED_STATS["hashing_full_fallback"] += 1
        EMBED_STATS["hashing_vectors"] += len(vecs)
        return vecs

    from openai import OpenAI
    import httpx

    out: List[List[float]] = []
    old = set_temp_env_for_proxy(use_env_proxy, https_proxy, http_proxy, no_proxy)
    try:
        http_client = httpx.Client(timeout=timeout_sec, trust_env=use_env_proxy)
        client = OpenAI(api_key=(api_key or "no-key"), base_url=base_url, http_client=http_client)
        for i in range(0, len(norm), max(1, bs)):
            chunk = norm[i : i + max(1, bs)]
            try:
                resp = client.embeddings.create(model=model, input=chunk, timeout=timeout_sec)
                data = getattr(resp, "data", None) or []
                indexed: List[Tuple[int, List[float]]] = []
                for item in data:
                    idx = int(getattr(item, "index", len(indexed)))
                    vec = getattr(item, "embedding", None)
                    indexed.append((idx, list(vec)))
                indexed.sort(key=lambda x: x[0])
                part = [v for _, v in indexed]
                if len(part) != len(chunk):
                    raise RuntimeError(f"embeddings 返回条数不一致 {len(part)} != {len(chunk)}")
                out.extend(part)
                EMBED_STATS["remote_batches"] += 1
                EMBED_STATS["remote_texts"] += len(chunk)
            except Exception:
                # 任何失败回退 hashing（与项目一致：避免阻塞，但需注意与旧向量混用维度差异）
                vecs = embed_texts_hashing(norm, dim=384)
                EMBED_STATS["hashing_full_fallback"] += 1
                EMBED_STATS["hashing_vectors"] += len(vecs)
                return vecs
    finally:
        restore_env(old)

    # 维度一致性校验
    if out:
        d0 = len(out[0])
        for j, row in enumerate(out):
            if len(row) != d0:
                raise RuntimeError(f"embedding 向量维度不一致 row0={d0} row{j}={len(row)}")
    return out

