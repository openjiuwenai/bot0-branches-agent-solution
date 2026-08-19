"""知识质检配置桥接（简化版）。"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

import yaml
from dotenv import load_dotenv

from rag_extract_split.config.llmkit_manager import get_llm_config
from rag_extract_split.config.embedding_manager import get_embedding_config

_REPO_ROOT = Path(__file__).resolve().parents[2]

_KQC_DEFAULTS = {
    "rules_file": "config/knowledge_qc/rules.yaml",
    "env_file": ".env",
    "wordlists_dir": "config/knowledge_qc/wordlists",
    "chroma_dir": "data/knowledge_qc/chroma",
}


def repo_root() -> Path:
    return _REPO_ROOT


def _abs_repo_path(rel: str) -> Path:
    p = Path(rel)
    if p.is_absolute():
        return p
    return (_REPO_ROOT / p).resolve()


def get_paths() -> Dict[str, Path]:
    chroma = _abs_repo_path(_KQC_DEFAULTS["chroma_dir"])
    chroma.mkdir(parents=True, exist_ok=True)
    return {
        "repo_root": _REPO_ROOT,
        "project_root": _REPO_ROOT,
        "rules_path": _abs_repo_path(_KQC_DEFAULTS["rules_file"]),
        "env_path": _abs_repo_path(_KQC_DEFAULTS["env_file"]),
        "wordlists_dir": _abs_repo_path(_KQC_DEFAULTS["wordlists_dir"]),
        "chroma_dir": chroma,
    }


def apply_kqc_config() -> None:
    """应用环境变量与 .env 配置。"""
    paths = get_paths()
    if paths["env_path"].is_file():
        load_dotenv(paths["env_path"], override=True)
    chroma_dir = os.getenv("CHROMA_PERSIST_DIR")
    if not chroma_dir:
        os.environ["CHROMA_PERSIST_DIR"] = str(paths["chroma_dir"])
    elif not Path(chroma_dir).is_absolute():
        os.environ["CHROMA_PERSIST_DIR"] = str(_abs_repo_path(chroma_dir))


def _parse_wordlist_lines(text: str) -> list:
    lines: list = []
    for line in (text or "").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            lines.append(line)
    return lines


def _project_default_env() -> Dict[str, str]:
    """从项目主配置（rag_extract_split.config.settings）读取 Embedding/LLM 默认值，
    供知识质检在没有 .env 或用户未填写时使用，避免重复配置。
    """
    try:
        from rag_extract_split.config.settings import CONFIG
    except Exception:
        return {}

    defaults: Dict[str, str] = {}
    emb = CONFIG.get("rag_embedding", {})
    llm = CONFIG.get("rag_llm", {})

    emb_mode = str(emb.get("mode", "remote")).lower()
    emb_request_mode = str(emb.get("request_mode", "openai")).lower()
    if emb_mode == "local":
        defaults["EMBEDDING_MODE"] = "local"
        defaults["EMBEDDING_MODEL"] = str(emb.get("local_model_dir", ""))
    elif emb_request_mode == "http_post":
        defaults["EMBEDDING_MODE"] = "http"
        defaults["HTTP_EMBEDDING_URL"] = str(
            emb.get("http_post_url") or emb.get("embedding_base_url") or ""
        )
        defaults["HTTP_EMBEDDING_DIM"] = str(emb.get("http_embedding_dim", "1536"))
    else:
        defaults["EMBEDDING_MODE"] = "openai"
        defaults["EMBEDDING_API_KEY"] = str(emb.get("embedding_api_key", ""))
        defaults["EMBEDDING_BASE_URL"] = str(emb.get("embedding_base_url", ""))
        defaults["EMBEDDING_MODEL"] = str(emb.get("embedding_model_name", ""))

    if emb.get("batch_size") is not None:
        defaults["EMBEDDING_BATCH_SIZE"] = str(emb["batch_size"])
    if emb.get("timeout_sec") is not None:
        defaults["API_TIMEOUT"] = str(emb["timeout_sec"])

    try:
        from rag_extract_split.config.llm_registry import get_llm_config

        llm = get_llm_config()
    except Exception:
        llm = CONFIG.get("rag_llm", {})

    llm_request_mode = str(llm.get("request_mode", "openai")).lower()
    if llm_request_mode == "http_post":
        defaults["LLM_MODE"] = "http"
        defaults["HTTP_LLM_URL"] = str(
            llm.get("http_post_url") or llm.get("base_url") or ""
        )
        defaults["HTTP_LLM_TOKEN"] = str(llm.get("api_key", ""))
    else:
        defaults["LLM_MODE"] = "openai"
        defaults["LLM_API_KEY"] = str(llm.get("api_key", ""))
        defaults["LLM_BASE_URL"] = str(llm.get("base_url", ""))
        defaults["LLM_MODEL"] = str(llm.get("model", ""))

    if llm.get("timeout_sec") is not None:
        try:
            llm_timeout = float(llm["timeout_sec"])
            existing = float(defaults.get("API_TIMEOUT") or 0)
            defaults["API_TIMEOUT"] = str(max(llm_timeout, existing))
        except Exception:
            defaults["API_TIMEOUT"] = str(llm["timeout_sec"])

    return defaults


def _apply_env_defaults(defaults: Dict[str, str]) -> None:
    """仅当环境变量缺失或为空时，填入项目默认值。"""
    for key, val in defaults.items():
        if not val:
            continue
        cur = os.getenv(key)
        if cur is None or cur == "":
            os.environ[key] = val


def _apply_env_override(env_override: Optional[Dict[str, Any]]) -> None:
    if not env_override:
        return
    for key, val in env_override.items():
        if not key:
            continue
        # 空字符串表示使用默认值：已设置的空值删除，未设置的保持项目/.env 默认值
        if val is None or str(val) == "":
            if os.getenv(key) == "":
                os.environ.pop(key, None)
            continue
        os.environ[str(key)] = str(val)


def _profile_env_from_llm_config(name: Optional[str]) -> Dict[str, str]:
    """把 llmkit LLM 配置转换为质检环境变量。"""
    if not name:
        return {}
    try:
        llm = get_llm_config(name)
    except Exception:
        return {}

    env: Dict[str, str] = {}
    request_mode = str(llm.get("request_mode", "openai")).lower()
    if request_mode == "http_post":
        env["LLM_MODE"] = "http"
        url = str(llm.get("http_post_url") or llm.get("base_url") or "")
        if url:
            env["HTTP_LLM_URL"] = url
        token = str(llm.get("api_key") or "")
        if token:
            env["HTTP_LLM_TOKEN"] = token
    else:
        env["LLM_MODE"] = "openai"
        api_key = str(llm.get("api_key") or "")
        if api_key:
            env["LLM_API_KEY"] = api_key
        base_url = str(llm.get("base_url") or "")
        if base_url:
            env["LLM_BASE_URL"] = base_url
        model = str(llm.get("model") or "")
        if model:
            env["LLM_MODEL"] = model

    if llm.get("timeout_sec") is not None:
        env["API_TIMEOUT"] = str(llm["timeout_sec"])

    return {k: v for k, v in env.items() if v}


def _profile_env_from_embedding_config(name: Optional[str]) -> Dict[str, str]:
    """把 llmkit Embedding 配置转换为质检环境变量。"""
    if not name:
        return {}
    try:
        emb = get_embedding_config(name)
    except Exception:
        return {}

    env: Dict[str, str] = {}
    mode = str(emb.get("mode", "remote")).lower()
    request_mode = str(emb.get("request_mode", "openai")).lower()

    if mode == "local":
        env["EMBEDDING_MODE"] = "local"
        model_dir = str(emb.get("local_model_dir") or "")
        if model_dir:
            env["EMBEDDING_MODEL"] = model_dir
    elif mode == "remote" and request_mode == "http_post":
        env["EMBEDDING_MODE"] = "http"
        url = str(emb.get("http_post_url") or emb.get("embedding_base_url") or "")
        if url:
            env["HTTP_EMBEDDING_URL"] = url
        dim = int(emb.get("dimensions", 1536) or 1536)
        env["HTTP_EMBEDDING_DIM"] = str(dim)
    else:
        env["EMBEDDING_MODE"] = "openai"
        api_key = str(emb.get("embedding_api_key") or emb.get("api_key") or "")
        if api_key:
            env["EMBEDDING_API_KEY"] = api_key
        base_url = str(emb.get("embedding_base_url") or "")
        if base_url:
            env["EMBEDDING_BASE_URL"] = base_url
        model = str(emb.get("embedding_model_name") or emb.get("model") or "")
        if model:
            env["EMBEDDING_MODEL"] = model

    if emb.get("batch_size") is not None:
        env["EMBEDDING_BATCH_SIZE"] = str(emb["batch_size"])
    if emb.get("timeout_sec") is not None:
        env["API_TIMEOUT"] = str(emb["timeout_sec"])

    return {k: v for k, v in env.items() if v}


def load_kqc_settings(
    rules_override: Optional[Dict[str, Any]] = None,
    env_override: Optional[Dict[str, Any]] = None,
    wordlists_override: Optional[Dict[str, str]] = None,
    llm_config_name: Optional[str] = None,
    embedding_config_name: Optional[str] = None,
) -> Dict[str, Any]:
    apply_kqc_config()
    _apply_env_defaults(_project_default_env())
    for key, val in _profile_env_from_llm_config(llm_config_name).items():
        os.environ[str(key)] = str(val)
    for key, val in _profile_env_from_embedding_config(embedding_config_name).items():
        os.environ[str(key)] = str(val)
    _apply_env_override(env_override)
    from backend.knowledge_qc.config import load_settings

    paths = get_paths()
    settings = load_settings(paths["rules_path"])
    if rules_override is not None:
        settings["rules"] = rules_override
    if wordlists_override is not None:
        settings["wordlist_overrides"] = {
            "regulatory": _parse_wordlist_lines(wordlists_override.get("regulatory", "")),
            "prohibited": _parse_wordlist_lines(wordlists_override.get("prohibited", "")),
        }
    return settings


_UI_ENV_KEYS = (
    "EMBEDDING_MODE",
    "LLM_MODE",
    "EMBEDDING_API_KEY",
    "EMBEDDING_BASE_URL",
    "EMBEDDING_MODEL",
    "EMBEDDING_BATCH_SIZE",
    "HTTP_EMBEDDING_URL",
    "HTTP_EMBEDDING_DIM",
    "LLM_API_KEY",
    "LLM_BASE_URL",
    "LLM_MODEL",
    "HTTP_LLM_URL",
    "HTTP_LLM_TOKEN",
    "HTTP_LLM_SESSION_ID",
    "HTTP_LLM_USER_ID",
    "HTTP_LLM_ENABLE_HISTORY",
    "CHROMA_PERSIST_DIR",
    "CHROMA_UPSERT_BATCH_SIZE",
    "API_TIMEOUT",
    "CHROMA_INIT_TIMEOUT",
)


def _parse_env_file(path: Path) -> Dict[str, str]:
    if not path.is_file():
        return {}
    result: Dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        result[key.strip()] = val.strip()
    return result


def read_env_file_dict() -> Dict[str, str]:
    """返回 API 环境页签的默认值：项目主配置 < .env < 用户输入。"""
    parsed = _parse_env_file(get_paths()["env_path"])
    defaults = _project_default_env()
    merged = {**defaults, **parsed}
    return {k: merged.get(k, "") for k in _UI_ENV_KEYS}


def rules_to_form_dict(rules: Dict[str, Any]) -> Dict[str, Any]:
    sim = rules.get("similarity") or {}
    length = rules.get("length") or {}
    chk = rules.get("checkers") or {}
    mode = rules.get("detection_mode") or {}
    id_cfg = rules.get("id") or {}
    qc_cfg = rules.get("qc") or {}
    return {
        "similarity": {
            "duplicate_threshold": sim.get("duplicate_threshold", 0.9),
            "conflict_threshold": sim.get("conflict_threshold", 0.78),
            "top_k": sim.get("top_k", 5),
            "query_batch_size": sim.get("query_batch_size", 32),
        },
        "length": {
            "min_chars": length.get("min_chars", 2),
            "max_chars": length.get("max_chars", 50),
        },
        "checkers": {
            "format": bool(chk.get("format", True)),
            "compliance": bool(chk.get("compliance", True)),
            "duplicate": bool(chk.get("duplicate", True)),
            "conflict": bool(chk.get("conflict", True)),
            "semantic": bool(chk.get("semantic", True)),
            "llm_semantic": bool(chk.get("llm_semantic", False)),
            "llm_dup_conflict": bool(chk.get("llm_dup_conflict", False)),
        },
        "qc": {
            "checkpoint_interval": int(qc_cfg.get("checkpoint_interval", 20)),
            "worker_count": int(qc_cfg.get("worker_count", 1)),
            "row_start": int(qc_cfg.get("row_start", 0) or 0),
            "row_end": int(qc_cfg.get("row_end", 0) or 0),
        },
        "detection_mode": {
            "batch": bool(mode.get("batch", True)),
            "production": bool(mode.get("production", True)),
        },
        "id": {"strategy": id_cfg.get("strategy", "uuid")},
    }


def merge_rules_payload(
    base_rules: Dict[str, Any], payload: Dict[str, Any]
) -> Dict[str, Any]:
    if not payload:
        return dict(base_rules)
    rules = dict(base_rules)
    if payload.get("yaml"):
        parsed = yaml.safe_load(payload["yaml"])
        if isinstance(parsed, dict):
            rules = dict(parsed)
    if payload.get("similarity"):
        rules.setdefault("similarity", {}).update(payload["similarity"])
    if payload.get("length"):
        rules.setdefault("length", {}).update(payload["length"])
    if payload.get("checkers"):
        rules.setdefault("checkers", {}).update(payload["checkers"])
    if payload.get("detection_mode"):
        rules.setdefault("detection_mode", {}).update(payload["detection_mode"])
    if payload.get("id"):
        rules.setdefault("id", {}).update(payload["id"])
    if payload.get("qc"):
        rules.setdefault("qc", {}).update(payload["qc"])
    return rules


def read_rules_yaml_text() -> str:
    path = get_paths()["rules_path"]
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")


def load_wordlist_text(name: str) -> str:
    path = get_paths()["wordlists_dir"] / f"{name}.txt"
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")
