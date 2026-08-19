#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from typing import Dict, List, Sequence, Tuple

from rag_extract_split.common.helpers import restore_env, set_temp_env_for_proxy


def extract_by_path(data: object, path: str) -> object:
    if not path:
        return data
    cur = data
    for token in [p for p in str(path).split(".") if p]:
        if isinstance(cur, list):
            try:
                idx = int(token)
            except Exception:
                return None
            if idx < 0 or idx >= len(cur):
                return None
            cur = cur[idx]
            continue
        if isinstance(cur, dict):
            if token not in cur:
                return None
            cur = cur[token]
            continue
        return None
    return cur


def embed_texts_http_post(
    norm: Sequence[str],
    cfg: Dict[str, object],
    vector_to_list,
    on_batch: object = None,
) -> List[List[float]]:
    import httpx

    base_url = str(cfg.get("embedding_base_url") or "").rstrip("/")
    post_url = str(cfg.get("http_post_url") or "").strip() or base_url
    model = str(cfg.get("embedding_model_name") or "").strip()
    api_key = str(cfg.get("embedding_api_key") or "")
    timeout_sec = int(cfg.get("timeout_sec") or 120)
    bs = int(cfg.get("batch_size") or 32)
    use_env_proxy = bool(cfg.get("use_env_proxy") or False)
    https_proxy = str(cfg.get("https_proxy") or "")
    http_proxy = str(cfg.get("http_proxy") or "")
    no_proxy = str(cfg.get("no_proxy") or "")

    if not post_url or not model:
        raise RuntimeError("embedding http_post 模式缺少 http_post_url/embedding_model_name")

    auth_header = str(cfg.get("http_post_auth_header") or "Authorization").strip() or "Authorization"
    auth_scheme = str(cfg.get("http_post_auth_scheme") or "Bearer").strip()
    vectors_path = str(cfg.get("http_post_vectors_path") or "data")
    vector_field = str(cfg.get("http_post_vector_field") or "embedding")
    index_field = str(cfg.get("http_post_index_field") or "index")
    extra_headers = cfg.get("http_post_extra_headers") or {}
    if not isinstance(extra_headers, dict):
        extra_headers = {}
    extra_body = cfg.get("http_post_extra_body") or {}
    if not isinstance(extra_body, dict):
        extra_body = {}

    out: List[List[float]] = []
    old = set_temp_env_for_proxy(use_env_proxy, https_proxy, http_proxy, no_proxy)
    try:
        with httpx.Client(timeout=timeout_sec, trust_env=use_env_proxy) as client:
            headers: Dict[str, str] = {"Content-Type": "application/json"}
            headers.update({str(k): str(v) for k, v in extra_headers.items()})
            if api_key:
                headers[auth_header] = (f"{auth_scheme} {api_key}".strip() if auth_scheme else api_key)
            for i in range(0, len(norm), max(1, bs)):
                chunk = list(norm[i:i + max(1, bs)])
                body: Dict[str, object] = {"model": model, "input": chunk}
                body.update(extra_body)
                resp = client.post(post_url, json=body, headers=headers)
                resp.raise_for_status()
                payload = resp.json()

                vectors_raw = extract_by_path(payload, vectors_path)
                if not isinstance(vectors_raw, list):
                    raise RuntimeError(f"embedding 响应路径 {vectors_path} 不是列表")

                part: List[List[float]] = []
                if vectors_raw and isinstance(vectors_raw[0], dict):
                    indexed: List[Tuple[int, List[float]]] = []
                    for j, item in enumerate(vectors_raw):
                        if not isinstance(item, dict):
                            continue
                        vec = item.get(vector_field)
                        if vec is None:
                            continue
                        idx_raw = item.get(index_field, j)
                        try:
                            idx = int(idx_raw)
                        except Exception:
                            idx = j
                        indexed.append((idx, vector_to_list(vec)))
                    indexed.sort(key=lambda x: x[0])
                    part = [v for _, v in indexed]
                else:
                    part = [vector_to_list(v) for v in vectors_raw]

                if len(part) != len(chunk):
                    raise RuntimeError(f"embedding http_post 返回条数不一致 {len(part)} != {len(chunk)}")
                out.extend(part)
                if callable(on_batch):
                    on_batch(len(chunk))
    finally:
        restore_env(old)
    return out
