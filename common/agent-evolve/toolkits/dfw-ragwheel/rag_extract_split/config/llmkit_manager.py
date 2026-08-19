#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LLM 配置管理器：在 rag_extract_split 中集成 llmkit。

对外提供：
- llmkit TemplateManager / ProfileManager 单例
- 从 Profile 到旧版 rag_llm 配置字典的转换（兼容现有 LLM 调用层）
- 初始化时自动迁移旧的 data/llm_configs.json 到 llmkit YAML 格式

该模块位于 rag_extract_split 包内，确保 CLI 与 Web 都能导入。
"""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from rag_extract_split.config.settings import CONFIG

from rag_extract_split.llmkit import (
    BUILTIN_TEMPLATES_DIR,
    Profile,
    ProfileManager,
    TemplateManager,
    call_llm_by_id,
    check_profile,
    get_default_data_dir,
)
from rag_extract_split.llmkit.caller import CallOverrides

logger = logging.getLogger(__name__)


class _LLMKitManager:
    """封装 llmkit 初始化、持久化和兼容适配。"""

    _instance: Optional["_LLMKitManager"] = None

    def __new__(cls, project_root: Optional[Path] = None) -> "_LLMKitManager":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self, project_root: Optional[Path] = None) -> None:
        if self._initialized:
            return
        if project_root is None:
            # 默认定位到项目根目录（rag_extract_split 的父目录）
            project_root = Path(__file__).resolve().parent.parent.parent
        self.project_root = project_root
        self.data_dir = get_default_data_dir(project_root)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.user_templates_dir = self.data_dir / "templates"
        self.user_templates_dir.mkdir(exist_ok=True)
        self.profiles_file = self.data_dir / "llm_profiles.yaml"

        self.template_manager = TemplateManager(
            BUILTIN_TEMPLATES_DIR, str(self.user_templates_dir)
        )
        self.profile_manager = ProfileManager(str(self.profiles_file))

        self._migrate_legacy_json()
        self._ensure_default_profile()
        self._initialized = True

    @classmethod
    def reset_singleton(cls) -> None:
        cls._instance = None
        cls._initialized = False

    def _migrate_legacy_json(self) -> None:
        """迁移旧版 data/llm_configs.json 到 llmkit YAML 配置文件。"""
        legacy_path = self.project_root / "data" / "llm_configs.json"
        if not legacy_path.exists():
            return
        try:
            raw = json.loads(legacy_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            logger.debug("failed to load legacy llm_configs.json", exc_info=True)
            return

        if not isinstance(raw, dict):
            return

        configs = raw.get("configs") or {}
        # active 名称仅用于排序展示，llmkit 本身不维护 active 概念
        active = raw.get("active") or "default"

        names = sorted(configs.keys())
        if "default" in names:
            names.remove("default")
            names.insert(0, "default")
        if active in configs and active != "default":
            names.remove(active)
            names.insert(1, active)

        existing = self.profile_manager.get_all_profiles()
        if existing:
            return

        template = self.template_manager.get_template("openai_compatible")
        if template is None:
            return

        for name in names:
            old_cfg = configs.get(name) or {}
            profile = self.convert_legacy_config_to_profile(name, old_cfg)
            if profile is not None:
                self.profile_manager.save_profile(profile)

        try:
            bak = legacy_path.with_suffix(".json.bak")
            legacy_path.rename(bak)
        except OSError:
            logger.warning("failed to rename legacy llm_configs.json to backup", exc_info=True)

        self.profile_manager.reload()

    def _ensure_default_profile(self) -> None:
        """确保存在一个名为 default 的配置（用 settings.py 中 rag_llm 填充）。"""
        profiles = self.profile_manager.get_all_profiles()
        if any(p.name == "default" for p in profiles):
            return

        template = self.template_manager.get_template("openai_compatible")
        if template is None:
            return

        old_cfg = CONFIG.get("rag_llm", {})
        profile = self.convert_legacy_config_to_profile("default", old_cfg)
        if profile is not None:
            self.profile_manager.save_profile(profile)

    @staticmethod
    def convert_legacy_config_to_profile(
        name: str, old_cfg: Dict[str, Any]
    ) -> Optional[Profile]:
        """把旧版 rag_llm 扁平配置转换为 llmkit Profile 对象。"""
        if not isinstance(old_cfg, dict):
            return None

        template_name = "openai_compatible"
        template = _LLMKitManager.load_builtin_template(template_name)
        if template is None:
            return None

        scaffold = template.generate_scaffold(name)
        request_mode = str(old_cfg.get("request_mode") or "openai").strip().lower()

        scaffold["connection"]["base_url"] = str(old_cfg.get("base_url") or "").rstrip("/")
        scaffold["connection"]["api_key"] = str(old_cfg.get("api_key") or "")
        scaffold["connection"]["timeout"] = int(old_cfg.get("timeout_sec") or 600)

        data = scaffold["request"].setdefault("data", {})
        data["model"] = str(old_cfg.get("model") or "gpt-4o-mini")
        if old_cfg.get("temperature") is not None:
            try:
                data["temperature"] = float(old_cfg["temperature"])
            except (TypeError, ValueError):
                logger.debug("invalid temperature in legacy llm config", exc_info=True)

        if request_mode == "http_post":
            scaffold.setdefault("runtime", {})
            scaffold["runtime"]["legacy_request_mode"] = "http_post"
            http_url = str(old_cfg.get("http_post_url") or "").rstrip("/")
            if http_url:
                scaffold["connection"]["base_url"] = http_url
                scaffold["request"]["url_suffix"] = ""
            auth_header = str(old_cfg.get("http_post_auth_header") or "Authorization")
            auth_scheme = str(old_cfg.get("http_post_auth_scheme") or "Bearer")
            scaffold["request"]["headers"] = {
                "Content-Type": "application/json",
                auth_header: f"{auth_scheme} ${{{'connection.api_key'}}}".strip(),
            }
            extra_headers = old_cfg.get("http_post_extra_headers") or {}
            if isinstance(extra_headers, dict):
                scaffold["request"]["headers"].update(extra_headers)
            extra_body = old_cfg.get("http_post_extra_body") or {}
            if isinstance(extra_body, dict):
                data.update(extra_body)
            content_path = str(
                old_cfg.get("http_post_content_path") or "choices.0.message.content"
            )
            usage_path = str(old_cfg.get("http_post_usage_path") or "usage")
            scaffold["runtime"]["http_post_content_path"] = content_path
            scaffold["runtime"]["http_post_usage_path"] = usage_path
        else:
            scaffold.setdefault("runtime", {})
            scaffold["runtime"]["legacy_request_mode"] = "openai"
            extra_body = old_cfg.get("extra_body") or {}
            if isinstance(extra_body, dict):
                data.update(extra_body)

        return Profile.from_dict(scaffold)

    @staticmethod
    def _convert_legacy_config_to_profile(
        name: str, old_cfg: Dict[str, Any]
    ) -> Optional[Profile]:
        return _LLMKitManager.convert_legacy_config_to_profile(name, old_cfg)

    @classmethod
    def load_builtin_template(cls, name: str) -> Optional[Any]:
        """获取模板对象（兼容未初始化时访问）。"""
        try:
            return TemplateManager(BUILTIN_TEMPLATES_DIR).get_template(name)
        except Exception:
            logger.debug("failed to load llmkit template %s", name, exc_info=True)
            return None


_manager: Optional[_LLMKitManager] = None


def get_manager(project_root: Optional[Path] = None) -> _LLMKitManager:
    global _manager
    if _manager is None:
        env_root = os.environ.get("DFW_RAG_HOME", "").strip()
        if env_root:
            project_root = Path(env_root)
        _manager = _LLMKitManager(project_root)
    return _manager


def reset_manager(project_root: Optional[Path] = None) -> _LLMKitManager:
    """重置管理器（主要用于测试）。"""
    global _manager
    _manager = None
    _LLMKitManager.reset_singleton()
    return get_manager(project_root)


def profile_to_legacy_cfg(profile: Profile) -> Dict[str, Any]:
    """把 llmkit Profile 转回旧版 rag_llm 配置字典，供现有 generation/llm.py 使用。"""
    conn = profile.connection or {}
    req = profile.request or {}
    data = req.get("data", {})
    runtime = profile.runtime or {}

    base_url = str(conn.get("base_url") or "").rstrip("/")
    url_suffix = str(req.get("url_suffix") or "")

    headers = req.get("headers") or {}
    auth_header = None
    auth_scheme = ""
    for k, v in headers.items():
        if isinstance(v, str) and "${connection.api_key}" in v:
            auth_header = k
            parts = v.split(" ", 1)
            auth_scheme = parts[0] if len(parts) > 1 else ""
            break

    raw_request_mode = str(runtime.get("legacy_request_mode") or "").strip().lower()
    if raw_request_mode in ("openai", "http_post"):
        is_http_post = raw_request_mode == "http_post"
    else:
        is_http_post = not url_suffix or auth_header != "Authorization"

    cfg: Dict[str, Any] = {
        "request_mode": "http_post" if is_http_post else "openai",
        "base_url": base_url,
        "api_key": str(conn.get("api_key") or ""),
        "model": str(data.get("model") or ""),
        "timeout_sec": int(conn.get("timeout") or 600),
        "use_env_proxy": bool(runtime.get("use_env_proxy") or False),
        "https_proxy": str(runtime.get("https_proxy") or ""),
        "http_proxy": str(runtime.get("http_proxy") or ""),
        "no_proxy": str(runtime.get("no_proxy") or ""),
        "knowledge_qc_http": bool(runtime.get("knowledge_qc_http") or False),
        "kqc_session_id": str(runtime.get("kqc_session_id") or "0"),
        "kqc_user_id": str(runtime.get("kqc_user_id") or "0"),
        "kqc_enable_history": str(runtime.get("kqc_enable_history") or "false").lower() == "true",
    }

    if is_http_post:
        cfg["http_post_url"] = base_url
        cfg["http_post_auth_header"] = auth_header or "Authorization"
        cfg["http_post_auth_scheme"] = auth_scheme or "Bearer"
        cfg["http_post_content_path"] = str(
            runtime.get("http_post_content_path") or "choices.0.message.content"
        )
        cfg["http_post_usage_path"] = str(
            runtime.get("http_post_usage_path") or "usage"
        )
        cfg["http_post_extra_headers"] = {
            k: v for k, v in headers.items() if k != auth_header and k != "Content-Type"
        }
        cfg["http_post_extra_body"] = {
            k: v for k, v in data.items() if k not in ("model", "messages")
        }
    else:
        cfg["http_post_url"] = ""
        cfg["http_post_auth_header"] = "Authorization"
        cfg["http_post_auth_scheme"] = "Bearer"
        cfg["http_post_content_path"] = "choices.0.message.content"
        cfg["http_post_usage_path"] = "usage"
        cfg["http_post_extra_headers"] = {}
        cfg["http_post_extra_body"] = {
            k: v for k, v in data.items() if k not in ("model", "messages")
        }

    return cfg


def get_llm_config(name: Optional[str] = None) -> Dict[str, Any]:
    """按名称读取 llmkit Profile 并转成旧版配置字典。"""
    manager = get_manager()
    if name:
        profile = manager.profile_manager.get_profile_by_name(name)
    else:
        profiles = manager.profile_manager.get_all_profiles()
        profile = profiles[0] if profiles else None

    if profile is None:
        return CONFIG.get("rag_llm", {})

    return profile_to_legacy_cfg(profile)


def list_config_names() -> List[str]:
    manager = get_manager()
    profiles = manager.profile_manager.get_all_profiles()
    names = [p.name for p in profiles]
    if "default" in names:
        names.remove("default")
        names.insert(0, "default")
    return names


def convert_legacy_config_to_profile(
    name: str, old_cfg: Dict[str, Any]
) -> Optional[Profile]:
    """把旧版 rag_llm 扁平配置转换为 llmkit Profile 对象。"""
    return _LLMKitManager.convert_legacy_config_to_profile(name, old_cfg)


def save_config_by_name(name: str, cfg: Dict[str, Any]) -> None:
    """保存/更新一个配置（cfg 为旧版 rag_llm 字典）。"""
    manager = get_manager()
    profile = convert_legacy_config_to_profile(name, cfg)
    if profile is None:
        raise ValueError("无法转换为 llmkit Profile")
    manager.profile_manager.save_profile(profile)


def delete_config_by_name(name: str) -> None:
    manager = get_manager()
    profile = manager.profile_manager.get_profile_by_name(name)
    if profile is None:
        raise ValueError(f"配置不存在: {name}")
    manager.profile_manager.delete_profile(profile.id)


def test_config_by_name(name: str) -> Dict[str, Any]:
    manager = get_manager()
    profile = manager.profile_manager.get_profile_by_name(name)
    if profile is None:
        return {"success": False, "message": f"配置不存在: {name}"}
    return check_profile(profile)


def call_llm_by_config_name(
    name: Optional[str], messages: List[Dict[str, str]]
) -> Any:
    manager = get_manager()
    if name:
        profile = manager.profile_manager.get_profile_by_name(name)
    else:
        profiles = manager.profile_manager.get_all_profiles()
        profile = profiles[0] if profiles else None
    if profile is None:
        raise ValueError(f"LLM 配置不存在: {name}")
    return call_llm_by_id(
        profile.id,
        messages=messages,
        profile_manager=manager.profile_manager,
        overrides=CallOverrides(stream_enabled=False),
    )
