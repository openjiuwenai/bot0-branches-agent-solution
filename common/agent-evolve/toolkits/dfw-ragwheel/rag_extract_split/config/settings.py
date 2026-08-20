#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from pathlib import Path
from typing import Any, Dict

# 以 settings.py 所在位置为基准，计算项目内本地模型目录的绝对路径
_SETTINGS_DIR = Path(__file__).resolve().parent
_PROJECT_ROOT = _SETTINGS_DIR.parent.parent
_LOCAL_MODEL_DIR = str(_PROJECT_ROOT / "models" / "bge-small-zh-v1.5")

CONFIG: Dict[str, Dict[str, Any]] = {
    "rag_extract": {
        "max_rounds": 200,
        # max_count 省略时与 rag_extract_service 一致：len(badcases) + 20
        "target_recall": 1.0,
        "top_k": 10,
        # 萃取完成后，用新 QA 回查原向量库，附带高相似命中了信息到最终 Excel
        "post_similarity_enabled": True,
        "post_similarity_top_k": 5,
        # 对 cosine 距离：值越小越相似；仅保留 <= 阈值的候选
        "post_similarity_distance_threshold": 0.2,
        # 知识补全模式：llm（默认）| cluster（纯聚类，不调用 LLM）
        "completion_mode": "llm",
        "llm_trace_enabled": True,
        "recall_eval_trace_enabled": True,
    },
    "allocation": {
        # 续轮余量分配策略：soft_inverse | gap_power | softmax | piecewise
        "strategy": "gap_power",
        # soft_inverse: w = mix * (1/(r+eps)^power) + (1-mix)*1
        "soft_inverse_eps": 0.03,
        "soft_inverse_mix": 0.55,
        "soft_inverse_power": 0.5,
        # gap_power: w = max(floor, (1-r)^p)
        "gap_power_p": 1,
        "gap_power_floor": 0.01,
        # softmax: score=(1-r), w ~ softmax(score/T)
        "softmax_temperature": 0.45,
        # piecewise: 按召回率区间给权重（从小到大匹配 max_rate）
        "piecewise_bands": [
            {"max_rate": 0.4, "weight": 3.0},
            {"max_rate": 0.7, "weight": 2.0},
            {"max_rate": 0.9, "weight": 1.0},
            {"max_rate": 1.0, "weight": 0.6},
        ],
    },
    "rag_llm": {
        # 请求模式：
        # - openai: 使用 OpenAI SDK 的 chat.completions（默认）
        # - http_post: 直接 HTTP POST 到指定模型服务 API
        "request_mode": "openai",
        # OpenAI 兼容 chat.completions；请在 Web 配置或环境变量中填写真实地址与密钥
        "base_url": "https://api.openai.com/v1",
        "api_key": "",
        "model": "gpt-4o-mini",
        # request_mode=http_post 时生效：
        # 若留空则回退使用 base_url 作为完整 POST URL
        "http_post_url": "",
        # API Key 的请求头拼接方式：<auth_header>: "<auth_scheme> <api_key>"
        "http_post_auth_header": "Authorization",
        "http_post_auth_scheme": "Bearer",
        # 额外请求头 / 请求体（会 merge 到默认 body）
        "http_post_extra_headers": {},
        "http_post_extra_body": {},
        # 响应字段路径（dot path）：用于提取文本与 usage
        # 默认兼容 OpenAI 风格：choices[0].message.content / usage
        "http_post_content_path": "choices.0.message.content",
        "http_post_usage_path": "usage",
        "timeout_sec": 60,
        "use_env_proxy": False,
        "https_proxy": "",
        "http_proxy": "",
        "no_proxy": "localhost,127.0.0.1",
    },
    "rag_embedding": {
        # embedding：默认使用本地 SentenceTransformer（目录见 local_model_dir）
        "mode": "local",  # local | remote
        # 远程访问模式（mode=remote 时生效）：
        # - openai: 使用 OpenAI SDK embeddings.create
        # - http_post: 直接 POST 到 embedding 服务
        "request_mode": "openai",
        "local_model_dir": _LOCAL_MODEL_DIR,
        "normalize_embeddings": False,
        # OpenAI 兼容 embeddings（当 mode=remote 时使用；或你自行切换）
        "embedding_model_name": "text-embedding-3-small",
        "embedding_api_key": "",
        "embedding_base_url": "https://api.openai.com/v1",
        # request_mode=http_post 时可配置（留空时使用 embedding_base_url）
        "http_post_url": "",
        "http_post_auth_header": "Authorization",
        "http_post_auth_scheme": "Bearer",
        "http_post_extra_headers": {},
        "http_post_extra_body": {},
        # 响应解析（dot path）
        "http_post_vectors_path": "data",
        "http_post_vector_field": "embedding",
        "http_post_index_field": "index",
        "timeout_sec": 120,
        "batch_size": 32,
        "use_env_proxy": False,
        "https_proxy": "",
        "http_proxy": "",
        "no_proxy": "",
    },
    "chroma": {
        # host 为空 => 本地持久化目录 data/chromadb（或由 CHROMA_DB_PATH 覆盖）
        # host 非空 => 远程 HTTP Chroma
        "host": "",
        "port": 8000,
        "ssl": False,
        "persist_dir": "data/chromadb",
        # 向量相似度空间（Chroma HNSW，建库后不可改；换参数需删库或改名）
        # 可选：l2（平方欧氏距离）、cosine（余弦距离）、ip（内积；常与归一化向量配合）
        "hnsw_space": "cosine",
        # 向量库读写默认批大小（所有模块可复用，CLI 可覆盖）
        "write_batch_size": 128,
        "query_batch_size": 128,
        "delete_batch_size": 1000,
    },
    "logging": {
        "dir": "logs",
        "llm_trace_file": "rag_extract_llm_trace.log",
        "recall_trace_file": "rag_extract_recall_trace.log",
        "cluster_trace_file": "rag_extract_cluster_trace.log",
    },
}

