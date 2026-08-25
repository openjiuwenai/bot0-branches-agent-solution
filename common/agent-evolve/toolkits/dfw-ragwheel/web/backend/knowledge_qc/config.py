from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List

import yaml

_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_RULES = _REPO_ROOT / "config" / "knowledge_qc" / "rules.yaml"
_DEFAULT_WORDLISTS = _REPO_ROOT / "config" / "knowledge_qc" / "wordlists"
_DEFAULT_CHROMA = _REPO_ROOT / "data" / "knowledge_qc" / "chroma"


def repo_root() -> Path:
    return _REPO_ROOT


def _env(key: str, fallback_key: str = None, default: str = "") -> str:
    val = os.getenv(key, "")
    if val:
        return val
    if fallback_key:
        return os.getenv(fallback_key, default)
    return default


def _env_int(key: str, default: int = 0) -> int:
    """读取整数型环境变量；空字符串或缺失时使用默认值。"""
    val = os.getenv(key)
    return int(val) if val else default


def _env_float(key: str, default: float = 0.0) -> float:
    """读取浮点型环境变量；空字符串或缺失时使用默认值。"""
    val = os.getenv(key)
    return float(val) if val else default


def load_settings(rules_path: Path = None) -> Dict[str, Any]:
    """加载规则与运行时配置（环境变量从 web/.env 或进程环境读取）。"""
    path = rules_path or _DEFAULT_RULES
    with open(path, "r", encoding="utf-8") as f:
        rules = yaml.safe_load(f) or {}

    chroma_dir = _env("CHROMA_PERSIST_DIR", default=str(_DEFAULT_CHROMA))
    if chroma_dir and not Path(chroma_dir).is_absolute():
        chroma_dir = str((_REPO_ROOT / chroma_dir).resolve())

    return {
        "rules": rules,
        "embedding_api_key": _env("EMBEDDING_API_KEY", "OPENAI_API_KEY"),
        "embedding_base_url": _env(
            "EMBEDDING_BASE_URL", "OPENAI_BASE_URL", "https://api.openai.com/v1"
        ),
        "embedding_model": _env("EMBEDDING_MODEL", default="text-embedding-3-small"),
        "llm_api_key": _env("LLM_API_KEY", "OPENAI_API_KEY"),
        "llm_base_url": _env(
            "LLM_BASE_URL", "OPENAI_BASE_URL", "https://api.openai.com/v1"
        ),
        "llm_model": _env("LLM_MODEL", default="gpt-4o-mini"),
        "embedding_mode": _env("EMBEDDING_MODE", default="openai").lower(),
        "llm_mode": _env("LLM_MODE", default="openai").lower(),
        "embedding_batch_size": _env_int("EMBEDDING_BATCH_SIZE", 32),
        "chroma_upsert_batch_size": _env_int("CHROMA_UPSERT_BATCH_SIZE", 100),
        "http_embedding_url": _env("HTTP_EMBEDDING_URL"),
        "http_embedding_dim": _env_int("HTTP_EMBEDDING_DIM", 1536),
        "http_llm_url": _env("HTTP_LLM_URL"),
        "http_llm_token": _env("HTTP_LLM_TOKEN"),
        "http_llm_session_id": _env("HTTP_LLM_SESSION_ID", default="0"),
        "http_llm_user_id": _env("HTTP_LLM_USER_ID", default="0"),
        "http_llm_enable_history": _env("HTTP_LLM_ENABLE_HISTORY", default="false"),
        "api_timeout": _env_float("API_TIMEOUT", 120.0),
        "chroma_init_timeout": _env_float("CHROMA_INIT_TIMEOUT", 15.0),
        "chroma_persist_dir": chroma_dir,
        "project_root": _REPO_ROOT,
    }


def save_rules(rules: Dict[str, Any], rules_path: Path = None) -> None:
    path = rules_path or _DEFAULT_RULES
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        yaml.dump(
            rules,
            f,
            allow_unicode=True,
            default_flow_style=False,
            sort_keys=False,
        )


def wordlist_path(name: str) -> Path:
    return _DEFAULT_WORDLISTS / f"{name}.txt"


def save_wordlist(name: str, text: str, wordlists_dir: Path = None) -> None:
    base = wordlists_dir or _DEFAULT_WORDLISTS
    base.mkdir(parents=True, exist_ok=True)
    path = base / f"{name}.txt"
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def load_wordlist(name: str, wordlists_dir: Path = None) -> List[str]:
    path = (wordlists_dir or _DEFAULT_WORDLISTS) / f"{name}.txt"
    if not path.exists():
        return []
    lines = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                lines.append(line)
    return lines
