#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Embedding 配置管理器：在 rag_extract_split 中独立管理 Embedding 模型配置。

对外提供：
- 独立的 ProfileManager 单例（持久化到 data/llmkit/embedding_profiles.yaml）
- 从 embedding_openai_compatible 模板创建/保存/删除配置
- 独立的“激活配置”概念（持久化到 data/llmkit/embedding_active.txt）
- 将 llmkit Profile 转换为旧版 rag_embedding 配置字典，供 embed_texts() 使用
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from rag_extract_split.config.settings import CONFIG

from rag_extract_split.llmkit import (
    BUILTIN_TEMPLATES_DIR,
    Profile,
    ProfileManager,
    TemplateManager,
    get_default_data_dir,
)


EMBEDDING_TEMPLATE_NAME = "embedding_openai_compatible"
DEFAULT_PROFILE_NAME = "default"


class _EmbeddingConfigManager:
    """封装 Embedding 配置的初始化、持久化和兼容适配。"""

    _instance: Optional["_EmbeddingConfigManager"] = None

    def __new__(cls, project_root: Optional[Path] = None) -> "_EmbeddingConfigManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self, project_root: Optional[Path] = None) -> None:
        if self._initialized:
            return
        if project_root is None:
            project_root = Path(__file__).resolve().parent.parent.parent
        self.project_root = project_root
        self.data_dir = get_default_data_dir(project_root)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.user_templates_dir = self.data_dir / "templates"
        self.user_templates_dir.mkdir(exist_ok=True)
        self.profiles_file = self.data_dir / "embedding_profiles.yaml"
        self.active_file = self.data_dir / "embedding_active.txt"

        self.template_manager = TemplateManager(
            BUILTIN_TEMPLATES_DIR, str(self.user_templates_dir)
        )
        self.profile_manager = ProfileManager(str(self.profiles_file))

        self._ensure_default_profile()
        self._initialized = True

    def _template(self) -> Optional[Any]:
        return self.template_manager.get_template(EMBEDDING_TEMPLATE_NAME)

    def _ensure_default_profile(self) -> None:
        """确保存在一个名为 default 的 Embedding 配置（用 settings.py 中 rag_embedding 填充）。"""
        profiles = self.profile_manager.get_all_profiles()
        if any(p.name == DEFAULT_PROFILE_NAME for p in profiles):
            return

        template = self._template()
        if template is None:
            return

        old_cfg = CONFIG.get("rag_embedding", {})
        profile = self._convert_legacy_embedding_to_profile(DEFAULT_PROFILE_NAME, old_cfg)
        if profile is not None:
            self.profile_manager.save_profile(profile)

    @staticmethod
    def _convert_legacy_embedding_to_profile(
        name: str, old_cfg: Dict[str, Any]
    ) -> Optional[Profile]:
        """把旧版 rag_embedding 扁平配置转换为 llmkit Profile 对象。"""
        if not isinstance(old_cfg, dict):
            return None

        template = _EmbeddingConfigManager._template_for(EMBEDDING_TEMPLATE_NAME)
        if template is None:
            return None

        scaffold = template.generate_scaffold(name)

        old_mode = str(old_cfg.get("mode") or "local").strip().lower()
        old_request_mode = str(old_cfg.get("request_mode") or "openai").strip().lower()

        # 映射旧版 mode 到新版 connection.mode
        if old_mode == "local":
            conn_mode = "local"
        elif old_request_mode == "http_post":
            conn_mode = "http"
        else:
            conn_mode = "openai"
        scaffold["connection"]["mode"] = conn_mode

        local_dir = str(old_cfg.get("local_model_dir") or "").strip()
        base_url = str(old_cfg.get("embedding_base_url") or "").rstrip("/")
        if conn_mode == "local":
            scaffold["connection"]["base_url"] = local_dir or base_url
        else:
            scaffold["connection"]["base_url"] = base_url or ""

        scaffold["connection"]["api_key"] = str(old_cfg.get("embedding_api_key") or "")
        scaffold["connection"]["timeout"] = int(old_cfg.get("timeout_sec") or 120)

        data = scaffold["request"].setdefault("data", {})
        data["model"] = str(old_cfg.get("embedding_model_name") or "")

        runtime = scaffold.setdefault("runtime", {})
        runtime["batch_size"] = int(old_cfg.get("batch_size") or 32)
        runtime["normalize_embeddings"] = bool(
            old_cfg.get("normalize_embeddings") if "normalize_embeddings" in old_cfg else True
        )

        # http 模式额外字段
        if conn_mode == "http":
            runtime["http_post_url"] = str(old_cfg.get("http_post_url") or base_url)
            runtime["http_post_auth_header"] = str(
                old_cfg.get("http_post_auth_header") or "Authorization"
            )
            runtime["http_post_auth_scheme"] = str(
                old_cfg.get("http_post_auth_scheme") or "Bearer"
            )
            runtime["http_post_extra_headers"] = dict(old_cfg.get("http_post_extra_headers") or {})
            runtime["http_post_extra_body"] = dict(old_cfg.get("http_post_extra_body") or {})
            runtime["http_post_vectors_path"] = str(
                old_cfg.get("http_post_vectors_path") or "data"
            )
            runtime["http_post_vector_field"] = str(
                old_cfg.get("http_post_vector_field") or "embedding"
            )
            runtime["http_post_index_field"] = str(
                old_cfg.get("http_post_index_field") or "index"
            )

        return Profile.from_dict(scaffold)

    @classmethod
    def _template_for(cls, name: str) -> Optional[Any]:
        """获取模板对象（兼容未初始化时访问）。"""
        try:
            return TemplateManager(BUILTIN_TEMPLATES_DIR).get_template(name)
        except Exception:
            return None

    # ---------- active config ----------

    def get_active_name(self) -> str:
        """读取当前激活的配置名称，不存在时返回 default。"""
        if self.active_file.exists():
            try:
                name = self.active_file.read_text(encoding="utf-8").strip()
                if name and self.profile_manager.get_profile_by_name(name) is not None:
                    return name
            except Exception:
                pass
        profiles = self.profile_manager.get_all_profiles()
        if profiles:
            return profiles[0].name
        return DEFAULT_PROFILE_NAME

    def set_active_name(self, name: str) -> None:
        """设置激活的配置名称。"""
        name = str(name).strip()
        if not name:
            raise ValueError("配置名称不能为空")
        if self.profile_manager.get_profile_by_name(name) is None:
            raise ValueError(f"配置不存在: {name}")
        self.active_file.write_text(name, encoding="utf-8")

    # ---------- profile CRUD helpers ----------

    def _convert_legacy_dict_to_profile(
        self, name: str, old_cfg: Dict[str, Any]
    ) -> Optional[Profile]:
        return self._convert_legacy_embedding_to_profile(name, old_cfg)


_manager: Optional[_EmbeddingConfigManager] = None


def get_manager(project_root: Optional[Path] = None) -> _EmbeddingConfigManager:
    global _manager
    if _manager is None:
        env_root = os.environ.get("DFW_RAG_HOME", "").strip()
        if env_root:
            project_root = Path(env_root)
        _manager = _EmbeddingConfigManager(project_root)
    return _manager


def reset_manager(project_root: Optional[Path] = None) -> _EmbeddingConfigManager:
    """重置管理器（主要用于测试）。"""
    global _manager
    _manager = None
    _EmbeddingConfigManager._instance = None
    _EmbeddingConfigManager._initialized = False
    return get_manager(project_root)


def profile_to_rag_embedding_cfg(profile: Profile) -> Dict[str, Any]:
    """把 llmkit Profile 转回旧版 rag_embedding 配置字典，供 embed_texts() 使用。"""
    conn = profile.connection or {}
    req = profile.request or {}
    data = req.get("data", {})
    runtime = profile.runtime or {}

    mode = str(conn.get("mode") or "openai").strip().lower()
    base_url = str(conn.get("base_url") or "").rstrip("/")

    if mode == "local":
        old_mode = "local"
        old_request_mode = "openai"  # 本地模型不走到远程分支
        local_model_dir = base_url
    elif mode == "http":
        old_mode = "remote"
        old_request_mode = "http_post"
        local_model_dir = ""
    else:
        old_mode = "remote"
        old_request_mode = "openai"
        local_model_dir = ""

    cfg: Dict[str, Any] = {
        "name": profile.name,
        "embedding_config_name": profile.name,
        "mode": old_mode,
        "request_mode": old_request_mode,
        "local_model_dir": local_model_dir,
        "normalize_embeddings": bool(
            runtime.get("normalize_embeddings")
            if "normalize_embeddings" in runtime
            else True
        ),
        "embedding_base_url": base_url,
        "embedding_model_name": str(data.get("model") or ""),
        "embedding_api_key": str(conn.get("api_key") or ""),
        "timeout_sec": int(conn.get("timeout") or 120),
        "batch_size": int(runtime.get("batch_size") or 32),
        "use_env_proxy": bool(runtime.get("use_env_proxy") or False),
        "https_proxy": str(runtime.get("https_proxy") or ""),
        "http_proxy": str(runtime.get("http_proxy") or ""),
        "no_proxy": str(runtime.get("no_proxy") or ""),
    }

    # 维度字段（OpenAI 兼容接口可选）
    dimensions = data.get("dimensions")
    if dimensions is not None:
        try:
            cfg["dimensions"] = int(dimensions)
        except Exception:
            pass

    # http 模式额外字段
    if old_request_mode == "http_post":
        cfg["http_post_url"] = str(runtime.get("http_post_url") or base_url)
        cfg["http_post_auth_header"] = str(
            runtime.get("http_post_auth_header") or "Authorization"
        )
        cfg["http_post_auth_scheme"] = str(runtime.get("http_post_auth_scheme") or "Bearer")
        cfg["http_post_extra_headers"] = dict(runtime.get("http_post_extra_headers") or {})
        cfg["http_post_extra_body"] = dict(runtime.get("http_post_extra_body") or {})
        cfg["http_post_vectors_path"] = str(runtime.get("http_post_vectors_path") or "data")
        cfg["http_post_vector_field"] = str(runtime.get("http_post_vector_field") or "embedding")
        cfg["http_post_index_field"] = str(runtime.get("http_post_index_field") or "index")

    return cfg


def list_embedding_configs() -> List[Dict[str, Any]]:
    """返回所有 Embedding 配置摘要列表。"""
    manager = get_manager()
    profiles = manager.profile_manager.get_all_profiles()
    active_name = manager.get_active_name()
    items = []
    for p in profiles:
        conn = p.connection or {}
        req = p.request or {}
        data = req.get("data", {})
        api_key = str(conn.get("api_key") or "")
        items.append({
            "id": p.id,
            "name": p.name,
            "template": p.template,
            "mode": conn.get("mode", "openai"),
            "base_url": conn.get("base_url", ""),
            "model": data.get("model", ""),
            "api_key_mask": _mask_api_key(api_key),
            "active": p.name == active_name,
        })
    return items


def get_embedding_config(name: Optional[str] = None) -> Dict[str, Any]:
    """按名称读取 Embedding 配置并转成旧版 rag_embedding 字典。"""
    manager = get_manager()
    if name:
        profile = manager.profile_manager.get_profile_by_name(name)
    else:
        profile = manager.profile_manager.get_profile_by_name(manager.get_active_name())

    if profile is None:
        return CONFIG.get("rag_embedding", {})

    return profile_to_rag_embedding_cfg(profile)


def get_active_embedding_config() -> Dict[str, Any]:
    """读取当前激活的 Embedding 配置（旧版字典）。"""
    return get_embedding_config(None)


def set_active_embedding_config(name: str) -> None:
    """设置当前激活的 Embedding 配置名称。"""
    get_manager().set_active_name(name)


def save_embedding_config(name: str, cfg: Dict[str, Any]) -> Profile:
    """保存/更新一个 Embedding 配置（cfg 为旧版 rag_embedding 字典）。"""
    manager = get_manager()
    profile = _EmbeddingConfigManager._convert_legacy_embedding_to_profile(name, cfg)
    if profile is None:
        raise ValueError("无法转换为 llmkit Profile")
    return manager.profile_manager.save_profile(profile)


def delete_embedding_config(name: str) -> None:
    """删除一个 Embedding 配置。"""
    manager = get_manager()
    profile = manager.profile_manager.get_profile_by_name(name)
    if profile is None:
        raise ValueError(f"配置不存在: {name}")
    manager.profile_manager.delete_profile(profile.id)


def get_embedding_profile_by_name(name: str) -> Optional[Profile]:
    """按名称读取原始 Profile 对象。"""
    return get_manager().profile_manager.get_profile_by_name(name)


def _mask_api_key(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 4:
        return "*" * len(value)
    return "*" * (len(value) - 4) + value[-4:]
