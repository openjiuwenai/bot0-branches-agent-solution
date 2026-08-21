#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LLM 配置注册表（llmkit 兼容层）。

原 JSON 注册表现在由 llmkit YAML Profile 管理替代。
所有读写操作都委托给 rag_extract_split.config.llmkit_manager，
保持原有函数签名不变，供 CLI 与业务代码无感迁移。
"""
from __future__ import annotations

import copy
from pathlib import Path
from typing import Any, Dict, List, Optional

from rag_extract_split.config.llmkit_manager import (
    delete_config_by_name as _delete_config,
    get_llm_config as _get_llm_config,
    get_manager as _get_manager,
    list_config_names as _list_config_names,
    profile_to_legacy_cfg,
    save_config_by_name as _save_config,
)
from rag_extract_split.config.settings import CONFIG


LLM_CONFIG_PATH: Path = Path("data/llm_configs.json")
DEFAULT_CONFIG_NAME: str = "default"


def _config_path() -> Path:
    """解析配置文件绝对路径，优先使用 DFW_RAG_HOME 环境变量。

    注：实际配置已迁移到 data/llmkit/llm_profiles.yaml，
    本函数仅用于兼容旧的路径解析逻辑。
    """
    import os

    home = os.environ.get("DFW_RAG_HOME", "").strip()
    if home:
        return Path(home) / LLM_CONFIG_PATH
    project_root = Path(__file__).resolve().parent.parent.parent
    return project_root / LLM_CONFIG_PATH


def _base_llm_config() -> Dict[str, Any]:
    """返回当前 CONFIG 中的 rag_llm 作为默认基准。"""
    return copy.deepcopy(CONFIG.get("rag_llm", {}))


def load_registry(path: Optional[Path] = None) -> Dict[str, Any]:
    """以 llmkit Profile 为基础构造兼容旧版格式的注册表字典。"""
    manager = _get_manager()
    profiles = manager.profile_manager.get_all_profiles()
    configs: Dict[str, Any] = {}
    active: Optional[str] = None
    for idx, profile in enumerate(profiles):
        configs[profile.name] = profile_to_legacy_cfg(profile)
        if idx == 0:
            active = profile.name

    if DEFAULT_CONFIG_NAME not in configs:
        configs[DEFAULT_CONFIG_NAME] = _base_llm_config()
    if not active:
        active = DEFAULT_CONFIG_NAME

    return {"active": active, "configs": configs}


def save_registry(registry: Dict[str, Any], path: Optional[Path] = None) -> None:
    """保存注册表（兼容旧接口，实际写入 llmkit YAML）。"""
    configs = registry.get("configs") or {}
    for name, cfg in configs.items():
        _save_config(name, cfg)


def list_config_names(path: Optional[Path] = None) -> List[str]:
    """返回所有配置名称（default 始终在最前）。"""
    names = _list_config_names()
    if DEFAULT_CONFIG_NAME in names:
        names.remove(DEFAULT_CONFIG_NAME)
        names.insert(0, DEFAULT_CONFIG_NAME)
    return names


def get_llm_config(name: Optional[str] = None, path: Optional[Path] = None) -> Dict[str, Any]:
    """按名称获取 LLM 配置；缺失的键使用 CONFIG["rag_llm"] 补齐。"""
    cfg = _get_llm_config(name)
    merged = _base_llm_config()
    merged.update(cfg)
    return merged


def set_llm_config(name: str, cfg: Dict[str, Any], path: Optional[Path] = None) -> None:
    """新增或更新一组 LLM 配置。"""
    if not name or not str(name).strip():
        raise ValueError("配置名称不能为空")
    _save_config(str(name).strip(), cfg)


def delete_llm_config(name: str, path: Optional[Path] = None) -> None:
    """删除一组 LLM 配置；default 不可删除。"""
    name = str(name).strip()
    if not name or name == DEFAULT_CONFIG_NAME:
        raise ValueError("不能删除 default 配置")
    _delete_config(name)


def set_active_config(name: str, path: Optional[Path] = None) -> None:
    """设置当前激活的配置名称（llmkit 无 active 概念，保留为 no-op）。"""
    name = str(name).strip()
    profiles = _get_manager().profile_manager.get_all_profiles()
    names = {p.name for p in profiles}
    if name not in names:
        raise ValueError(f"配置不存在: {name}")


def mask_config(cfg: Dict[str, Any]) -> Dict[str, Any]:
    """对展示用的配置做脱敏处理（api_key 只保留最后 4 位）。"""
    masked = copy.deepcopy(cfg)
    key = masked.get("api_key") or ""
    if key and len(key) > 4:
        masked["api_key"] = "*" * (len(key) - 4) + key[-4:]
    elif key:
        masked["api_key"] = "*" * len(key)
    return masked
