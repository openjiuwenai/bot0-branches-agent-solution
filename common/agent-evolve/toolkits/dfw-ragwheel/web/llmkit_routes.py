#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""llmkit 风格的 LLM 配置管理 API 与页面路由。

提供：
- 页面：/llm-configs（列表）、/llm-configs/new（新增/编辑弹窗在同一页）
- API：模板列表、模板表单、配置 CRUD、连接测试
"""
from __future__ import annotations

import traceback
from pathlib import Path
from typing import Any, Dict, List

from flask import Blueprint, jsonify, redirect, render_template, request

from rag_extract_split.config.llmkit_manager import (
    _LLMKitManager,
    call_llm_by_config_name,
    delete_config_by_name,
    get_llm_config,
    get_manager as get_llm_manager,
    list_config_names,
    profile_to_legacy_cfg,
    save_config_by_name,
    test_config_by_name,
)
from rag_extract_split.config.embedding_manager import (
    _EmbeddingConfigManager,
    delete_embedding_config,
    get_embedding_config,
    get_manager as get_embedding_manager,
    list_embedding_configs,
    profile_to_rag_embedding_cfg,
    save_embedding_config,
    set_active_embedding_config,
)
from web.config import config

llm_bp = Blueprint("llm", __name__)


# ============================================================
# Helpers
# ============================================================


def _get_manager():
    return get_llm_manager(config.PROJECT_ROOT)


def _get_embedding_manager():
    return get_embedding_manager(config.PROJECT_ROOT)


def _mask_api_key(value: str) -> str:
    if not value:
        return ""
    if len(value) <= 4:
        return "*" * len(value)
    return "*" * (len(value) - 4) + value[-4:]


def _profile_to_list_item(profile) -> Dict[str, Any]:
    """把 Profile 对象转成前端列表需要的摘要字典。"""
    conn = profile.connection or {}
    req = profile.request or {}
    data = req.get("data", {})
    return {
        "id": profile.id,
        "name": profile.name,
        "template": profile.template,
        "base_url": conn.get("base_url", ""),
        "model": data.get("model", ""),
        "api_key_mask": _mask_api_key(conn.get("api_key", "")),
    }


def _collect_profile_from_body(data: Dict[str, Any], template_name: str) -> Dict[str, Any]:
    """根据前端提交的扁平/嵌套字段和模板表单定义，组装完整 profile 字典。"""
    manager = _get_manager()
    template = manager.template_manager.get_template(template_name)
    if template is None:
        raise ValueError(f"模板不存在: {template_name}")

    scaffold = template.generate_scaffold(data.get("name", "未命名配置"))
    # 覆盖 id（编辑时保留）
    if data.get("id"):
        scaffold["id"] = data["id"]

    form_fields = template.form_fields
    for field in form_fields:
        key = field["key"]
        value = data.get(key)
        if value is None:
            continue
        parts = key.split(".")
        target = scaffold
        for part in parts[:-1]:
            target = target.setdefault(part, {})
        # 类型转换
        field_type = field.get("type")
        if field_type == "int":
            try:
                value = int(value)
            except Exception:
                value = 0
        elif field_type in ("number", "float"):
            try:
                value = float(value)
            except Exception:
                value = 0.0
        elif field_type == "checkbox":
            value = bool(value)
        target[parts[-1]] = value

    return scaffold


# ============================================================
# Page Routes
# ============================================================


@llm_bp.route("/llm-configs/")
def llm_configs_page():
    return render_template("llm_configs.html")


@llm_bp.route("/llm-configs/new")
def llm_config_new_page():
    return redirect("llm-configs/")


@llm_bp.route("/api/llm/templates", methods=["POST"])
def create_llm_template():
    """创建新的 LLM 用户自定义模板。"""
    data = request.get_json()
    if not data or "yaml" not in data:
        return jsonify({"success": False, "message": "缺少 yaml 字段"}), 400
    manager = _get_manager()
    try:
        template = manager.template_manager.save_user_template("", data["yaml"])
        return jsonify({
            "success": True,
            "template": {
                "name": template.name,
                "display_name": template.display_name,
            },
        })
    except Exception as e:
        return jsonify({"success": False, "message": f"保存模板失败: {e}"}), 400


@llm_bp.route("/api/embedding/templates", methods=["POST"])
def create_embedding_template():
    """创建新的 Embedding 用户自定义模板。"""
    data = request.get_json()
    if not data or "yaml" not in data:
        return jsonify({"success": False, "message": "缺少 yaml 字段"}), 400
    manager = _get_embedding_manager()
    try:
        template = manager.template_manager.save_user_template("", data["yaml"])
        return jsonify({
            "success": True,
            "template": {
                "name": template.name,
                "display_name": template.display_name,
            },
        })
    except Exception as e:
        return jsonify({"success": False, "message": f"保存模板失败: {e}"}), 400


# ============================================================
# Template APIs
# ============================================================


@llm_bp.route("/api/llm/templates", methods=["GET"])
def list_templates():
    manager = _get_manager()
    result = []
    for t in manager.template_manager.list_templates():
        template = manager.template_manager.get_template(t["name"])
        result.append({
            "name": t["name"],
            "display_name": t["display_name"],
            "description": t["description"],
            "version": t["version"],
            "form": template.form_fields if template else [],
        })
    return jsonify(result)


@llm_bp.route("/api/llm/templates/<name>/form", methods=["GET"])
def get_template_form(name: str):
    manager = _get_manager()
    template = manager.template_manager.get_template(name)
    if template is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify({"form": template.form_fields})


@llm_bp.route("/api/llm/templates/<name>/scaffold", methods=["GET"])
def get_template_scaffold(name: str):
    manager = _get_manager()
    template = manager.template_manager.get_template(name)
    if template is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify(template.generate_scaffold())


@llm_bp.route("/api/llm/templates/<name>/yaml", methods=["GET"])
def get_llm_template_yaml(name: str):
    """获取模板的原始 YAML 内容（支持用户自定义模板覆盖）。"""
    manager = _get_manager()
    path = manager.template_manager.get_template_path(name)
    if path is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify({"name": name, "yaml": path.read_text(encoding="utf-8")})


# ============================================================
# Profile APIs
# ============================================================


@llm_bp.route("/api/llm/profiles", methods=["GET"])
def list_profiles():
    manager = _get_manager()
    profiles = manager.profile_manager.get_all_profiles()
    return jsonify([_profile_to_list_item(p) for p in profiles])


@llm_bp.route("/api/llm/profiles/<profile_id>", methods=["GET"])
def get_profile(profile_id: str):
    manager = _get_manager()
    profile = manager.profile_manager.get_profile(profile_id)
    if profile is None:
        return jsonify({"error": "Profile not found"}), 404
    return jsonify(profile.to_dict())


@llm_bp.route("/api/llm/profiles", methods=["POST"])
def save_profile():
    data = request.get_json()
    if not data:
        return jsonify({"success": False, "message": "缺少请求体"}), 400

    template_name = data.get("template")
    if not template_name:
        return jsonify({"success": False, "message": "请选择模板"}), 400

    manager = _get_manager()
    template = manager.template_manager.get_template(template_name)
    if template is None:
        return jsonify({"success": False, "message": f"模板不存在: {template_name}"}), 400

    try:
        profile_dict = _collect_profile_from_body(data, template_name)
    except ValueError as e:
        return jsonify({"success": False, "message": str(e)}), 400

    errors = template.validate(profile_dict)
    if errors:
        return jsonify({"success": False, "message": "表单校验失败", "errors": errors}), 400

    # 编辑时删除旧名称（如果名称变更）
    original_name = str(data.get("original_name") or "").strip()
    new_name = str(profile_dict.get("name") or "").strip()
    if original_name and original_name != new_name:
        try:
            delete_config_by_name(original_name)
        except Exception:
            pass

    try:
        profile = manager.profile_manager.save_profile_dict(profile_dict)
        return jsonify({"success": True, "profile": _profile_to_list_item(profile)})
    except Exception as e:
        return jsonify({"success": False, "message": f"保存失败: {e}"}), 500


@llm_bp.route("/api/llm/profiles/<profile_id>", methods=["DELETE"])
def delete_profile(profile_id: str):
    manager = _get_manager()
    success = manager.profile_manager.delete_profile(profile_id)
    return jsonify({"success": success})


@llm_bp.route("/api/llm/profiles/<profile_id>/test", methods=["POST"])
def test_saved_profile(profile_id: str):
    manager = _get_manager()
    profile = manager.profile_manager.get_profile(profile_id)
    if profile is None:
        return jsonify({"success": False, "message": "配置不存在"}), 404
    try:
        result = test_config_by_name(profile.name)
        if not result.get("success", False):
            return jsonify({
                "success": False,
                "message": result.get("message") or "测试失败",
                "status_code": result.get("status_code"),
                "error": result.get("error"),
            }), 400 if result.get("status_code") else 502
        return jsonify(result)
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"测试失败: {e}",
            "error": {"type": type(e).__name__, "detail": traceback.format_exc()},
        }), 500


@llm_bp.route("/api/llm/profiles/test-draft", methods=["POST"])
def test_profile_draft():
    """测试未保存的配置草稿。"""
    data = request.get_json()
    if not data:
        return jsonify({"success": False, "message": "缺少请求体"}), 400

    template_name = data.get("template")
    if not template_name:
        return jsonify({"success": False, "message": "请选择模板"}), 400

    manager = _get_manager()
    template = manager.template_manager.get_template(template_name)
    if template is None:
        return jsonify({"success": False, "message": f"模板不存在: {template_name}"}), 400

    try:
        profile_dict = _collect_profile_from_body(data, template_name)
    except ValueError as e:
        return jsonify({"success": False, "message": str(e)}), 400

    errors = template.validate(profile_dict)
    if errors:
        return jsonify({"success": False, "message": "表单校验失败", "errors": errors}), 400

    try:
        profile = manager.profile_manager.save_profile_dict(profile_dict)
        result = test_config_by_name(profile.name)
        # 测试后删除临时草稿，避免污染配置列表
        manager.profile_manager.delete_profile(profile.id)
        if not result.get("success", False):
            return jsonify({
                "success": False,
                "message": result.get("message") or "测试失败",
                "status_code": result.get("status_code"),
                "error": result.get("error"),
            }), 400 if result.get("status_code") else 502
        return jsonify(result)
    except Exception as e:
        return jsonify({
            "success": False,
            "message": f"测试失败: {e}",
            "error": {"type": type(e).__name__, "detail": traceback.format_exc()},
        }), 500


# ============================================================
# Legacy-compatible APIs（保持 extract/synthesize 页面下拉框可用）
# ============================================================


@llm_bp.route("/api/llm-configs", methods=["GET"])
def list_legacy_configs():
    """旧版 API：返回 {success, items, active}。"""
    manager = _get_manager()
    profiles = manager.profile_manager.get_all_profiles()
    active_profile = profiles[0] if profiles else None
    items = []
    for p in profiles:
        cfg = profile_to_legacy_cfg(p)
        items.append({
            "name": p.name,
            "config": cfg,
            "active": active_profile is not None and p.id == active_profile.id,
        })
    return jsonify({"success": True, "items": items, "active": active_profile.name if active_profile else None})


@llm_bp.route("/api/llm-configs", methods=["POST"])
def save_legacy_config():
    """旧版 API：接收旧版扁平字段并保存为 llmkit profile。"""
    data = request.get_json() or request.form.to_dict() or {}
    name = str(data.get("name") or "").strip()
    if not name:
        return jsonify({"success": False, "message": "配置名称不能为空"}), 400

    old_cfg: Dict[str, Any] = {}
    for key in [
        "request_mode", "base_url", "api_key", "model", "timeout_sec",
        "use_env_proxy", "http_post_url", "http_post_auth_header",
        "http_post_auth_scheme", "http_post_content_path", "http_post_usage_path",
    ]:
        if key in data:
            old_cfg[key] = data[key]

    for key in ["http_post_extra_headers", "http_post_extra_body", "extra_body"]:
        value = data.get(key)
        if isinstance(value, str):
            try:
                import json as _json
                value = _json.loads(value)
            except Exception:
                value = {}
        if value:
            old_cfg[key] = value

    original_name = str(data.get("original_name") or "").strip()
    if original_name and original_name != name:
        try:
            delete_config_by_name(original_name)
        except Exception:
            pass

    try:
        save_config_by_name(name, old_cfg)
        return jsonify({"success": True, "message": f"配置 {name} 已保存", "name": name})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 400


@llm_bp.route("/api/llm-configs/<name>", methods=["DELETE"])
def remove_legacy_config(name: str):
    try:
        delete_config_by_name(name)
        return jsonify({"success": True, "message": f"配置 {name} 已删除"})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 400


@llm_bp.route("/api/llm-configs/<name>/active", methods=["POST"])
def activate_legacy_config(name: str):
    # llmkit 没有 active 概念，保留接口返回成功即可
    return jsonify({"success": True, "message": f"已切换至配置 {name}"})


@llm_bp.route("/api/llm-configs/<name>/test", methods=["POST"])
def test_legacy_config(name: str):
    try:
        result = test_config_by_name(name)
        return jsonify({
            "success": result.get("success", False),
            "message": result.get("message", ""),
            "response_preview": (result.get("response") or {}).get("choices", [{}])[0].get("message", {}).get("content", "")[:200]
            if isinstance(result.get("response"), dict)
            else str(result.get("response", ""))[:200],
        })
    except Exception as e:
        return jsonify({"success": False, "message": f"测试失败: {e}"}), 400


@llm_bp.route("/api/llm-configs/test", methods=["POST"])
def test_legacy_config_body():
    """旧版 API：根据请求体中的配置测试连通性，无需先保存。"""
    data = request.get_json() or request.form.to_dict() or {}
    name = f"_draft_{id(data)}"
    old_cfg: Dict[str, Any] = {}
    for key in [
        "request_mode", "base_url", "api_key", "model", "timeout_sec",
        "use_env_proxy", "http_post_url", "http_post_auth_header",
        "http_post_auth_scheme", "http_post_content_path", "http_post_usage_path",
    ]:
        if key in data:
            old_cfg[key] = data[key]

    for key in ["http_post_extra_headers", "http_post_extra_body", "extra_body"]:
        value = data.get(key)
        if isinstance(value, str):
            try:
                import json as _json
                value = _json.loads(value)
            except Exception:
                value = {}
        if value:
            old_cfg[key] = value

    manager = _get_manager()
    try:
        temp_profile = _LLMKitManager._convert_legacy_config_to_profile(name, old_cfg)
        if temp_profile is None:
            raise ValueError("配置格式错误")
        profile = manager.profile_manager.save_profile_dict(temp_profile.to_dict())
        result = test_config_by_name(profile.name)
        manager.profile_manager.delete_profile(profile.id)
        return jsonify({
            "success": result.get("success", False),
            "message": result.get("message", ""),
        })
    except Exception as e:
        return jsonify({"success": False, "message": f"测试失败: {e}"}), 400
    finally:
        try:
            manager.profile_manager.delete_profile(profile.id)
        except Exception:
            pass


@llm_bp.route("/api/llm-configs/template", methods=["GET"])
def llm_config_template():
    """旧版 API：下载配置模板。"""
    template = {
        "request_mode": "openai",
        "base_url": "https://api.openai.com/v1",
        "api_key": "sk-xxxxxxxxxxxxxxxxxxxxxxxx",
        "model": "gpt-4o-mini",
        "timeout_sec": 60,
        "use_env_proxy": False,
        "extra_body": {"enable_thinking": False},
        "http_post_url": "",
        "http_post_auth_header": "Authorization",
        "http_post_auth_scheme": "Bearer",
        "http_post_extra_headers": {},
        "http_post_extra_body": {},
        "http_post_content_path": "choices.0.message.content",
        "http_post_usage_path": "usage",
    }
    return jsonify(template)


def _format_embedding_test_error(exc: BaseException, cfg: Dict[str, Any]) -> Dict[str, Any]:
    """把 Embedding 测试异常转换为前端可展示的友好错误信息。"""
    import traceback
    msg = str(exc).lower()
    detail = traceback.format_exc()

    if "connection" in msg or "connect" in msg or "max retries" in msg or "refused" in msg or "10061" in msg or "10060" in msg or "目标计算机" in msg:
        base_url = cfg.get("embedding_base_url") or cfg.get("local_model_dir") or cfg.get("http_post_url") or ""
        return {
            "type": "connection_error",
            "message": f"无法连接到 Embedding 服务端点: {base_url}。请检查地址、端口或网络。",
            "detail": detail,
        }
    if "timeout" in msg:
        return {
            "type": "timeout",
            "message": "连接 Embedding 服务超时，请检查服务是否可用或增大超时时间。",
            "detail": detail,
        }
    if "authentication" in msg or "unauthorized" in msg or "api key" in msg or "401" in msg:
        return {
            "type": "auth_error",
            "message": "Embedding 服务认证失败，请检查 API Key 是否正确。",
            "detail": detail,
        }
    if "model" in msg and ("not found" in msg or "does not exist" in msg or "404" in msg):
        return {
            "type": "model_not_found",
            "message": f"Embedding 模型不存在: {cfg.get('embedding_model_name')}。请检查模型名称。",
            "detail": detail,
        }
    if "no such file" in msg or ("does not exist" in msg and cfg.get("mode") == "local") or "不是有效的" in msg or "sentence_transformers" in msg or "sentence-transformer" in msg or "model_name" in msg:
        if cfg.get("mode") == "local":
            return {
                "type": "model_dir_error",
                "message": f"本地 Embedding 模型目录不存在或无效: {cfg.get('local_model_dir') or cfg.get('embedding_base_url')}。",
                "detail": detail,
            }
        else:
            return {
                "type": "model_not_found",
                "message": f"Embedding 模型不存在: {cfg.get('embedding_model_name')}。请检查模型名称。",
                "detail": detail,
            }
    return {
        "type": type(exc).__name__,
        "message": f"测试失败: {exc}",
        "detail": detail,
    }


# ============================================================
# Embedding Config Pages
# ============================================================


@llm_bp.route("/embedding-configs/")
def embedding_configs_page():
    return render_template("embedding_configs.html")


@llm_bp.route("/embedding-configs/new")
def embedding_config_new_page():
    return redirect("embedding-configs/")


# ============================================================
# Embedding Template APIs
# ============================================================


@llm_bp.route("/api/embedding/templates", methods=["GET"])
def list_embedding_templates():
    manager = _get_embedding_manager()
    result = []
    for t in manager.template_manager.list_templates():
        template = manager.template_manager.get_template(t["name"])
        result.append({
            "name": t["name"],
            "display_name": t["display_name"],
            "description": t["description"],
            "version": t["version"],
            "form": template.form_fields if template else [],
        })
    return jsonify(result)


@llm_bp.route("/api/embedding/templates/<name>/form", methods=["GET"])
def get_embedding_template_form(name: str):
    manager = _get_embedding_manager()
    template = manager.template_manager.get_template(name)
    if template is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify({"form": template.form_fields})


@llm_bp.route("/api/embedding/templates/<name>/scaffold", methods=["GET"])
def get_embedding_template_scaffold(name: str):
    manager = _get_embedding_manager()
    template = manager.template_manager.get_template(name)
    if template is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify(template.generate_scaffold())


@llm_bp.route("/api/embedding/templates/<name>/yaml", methods=["GET"])
def get_embedding_template_yaml(name: str):
    """获取 Embedding 模板的原始 YAML 内容（支持用户自定义模板覆盖）。"""
    manager = _get_embedding_manager()
    path = manager.template_manager.get_template_path(name)
    if path is None:
        return jsonify({"error": "Template not found"}), 404
    return jsonify({"name": name, "yaml": path.read_text(encoding="utf-8")})


# ============================================================
# Embedding Profile APIs
# ============================================================


def _embedding_profile_to_list_item(profile) -> Dict[str, Any]:
    """把 Embedding Profile 对象转成前端列表需要的摘要字典。"""
    conn = profile.connection or {}
    req = profile.request or {}
    data = req.get("data", {})
    api_key = str(conn.get("api_key") or "")
    return {
        "id": profile.id,
        "name": profile.name,
        "template": profile.template,
        "mode": conn.get("mode", "openai"),
        "base_url": conn.get("base_url", ""),
        "model": data.get("model", ""),
        "api_key_mask": _mask_api_key(api_key),
    }


@llm_bp.route("/api/embedding/profiles", methods=["GET"])
def list_embedding_profiles():
    manager = _get_embedding_manager()
    profiles = manager.profile_manager.get_all_profiles()
    active_name = manager.get_active_name()
    items = []
    for p in profiles:
        item = _embedding_profile_to_list_item(p)
        item["active"] = p.name == active_name
        items.append(item)
    return jsonify(items)


@llm_bp.route("/api/embedding/profiles/<profile_id>", methods=["GET"])
def get_embedding_profile(profile_id: str):
    manager = _get_embedding_manager()
    profile = manager.profile_manager.get_profile(profile_id)
    if profile is None:
        return jsonify({"error": "Profile not found"}), 404
    return jsonify(profile.to_dict())


@llm_bp.route("/api/embedding/profiles", methods=["POST"])
def save_embedding_profile():
    data = request.get_json()
    if not data:
        return jsonify({"success": False, "message": "缺少请求体"}), 400

    template_name = data.get("template")
    if not template_name:
        return jsonify({"success": False, "message": "请选择模板"}), 400

    manager = _get_embedding_manager()
    template = manager.template_manager.get_template(template_name)
    if template is None:
        return jsonify({"success": False, "message": f"模板不存在: {template_name}"}), 400

    try:
        profile_dict = _collect_profile_from_body(data, template_name)
    except ValueError as e:
        return jsonify({"success": False, "message": str(e)}), 400

    errors = template.validate(profile_dict)
    if errors:
        return jsonify({"success": False, "message": "表单校验失败", "errors": errors}), 400

    # 编辑时删除旧名称（如果名称变更）
    original_name = str(data.get("original_name") or "").strip()
    new_name = str(profile_dict.get("name") or "").strip()
    if original_name and original_name != new_name:
        try:
            delete_embedding_config(original_name)
        except Exception:
            pass

    try:
        profile = manager.profile_manager.save_profile_dict(profile_dict)
        return jsonify({"success": True, "profile": _embedding_profile_to_list_item(profile)})
    except Exception as e:
        return jsonify({"success": False, "message": f"保存失败: {e}"}), 500


@llm_bp.route("/api/embedding/profiles/<profile_id>", methods=["DELETE"])
def delete_embedding_profile(profile_id: str):
    manager = _get_embedding_manager()
    success = manager.profile_manager.delete_profile(profile_id)
    return jsonify({"success": success})


@llm_bp.route("/api/embedding/profiles/<profile_id>/active", methods=["POST"])
def activate_embedding_profile(profile_id: str):
    manager = _get_embedding_manager()
    profile = manager.profile_manager.get_profile(profile_id)
    if profile is None:
        return jsonify({"success": False, "message": "配置不存在"}), 404
    try:
        set_active_embedding_config(profile.name)
        return jsonify({"success": True, "message": f"已切换至配置 {profile.name}"})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500


@llm_bp.route("/api/embedding/profiles/<profile_id>/test", methods=["POST"])
def test_saved_embedding_profile(profile_id: str):
    manager = _get_embedding_manager()
    profile = manager.profile_manager.get_profile(profile_id)
    if profile is None:
        return jsonify({"success": False, "message": "配置不存在"}), 404

    try:
        cfg = profile_to_rag_embedding_cfg(profile)
        # 简单验证：按模式尝试编码一条文本
        mode = cfg.get("mode")
        request_mode = cfg.get("request_mode")
        if mode == "local":
            from rag_extract_split.infrastructure.embedding import get_local_embed_func, _vector_to_list
            from pathlib import Path
            model_dir = cfg.get("local_model_dir") or cfg.get("embedding_base_url")
            if not model_dir:
                return jsonify({"success": False, "message": "local 模式缺少模型目录"})
            if not Path(model_dir).exists():
                return jsonify({
                    "success": False,
                    "message": f"本地 Embedding 模型目录不存在或无效: {model_dir}",
                    "error": {"type": "model_dir_error", "detail": f"Path not found: {model_dir}"},
                }), 400
            embf = get_local_embed_func(
                model_dir, normalize_embeddings=bool(cfg.get("normalize_embeddings", True))
            )
            vecs = embf(["测试文本", "hello world"])
            dim = len(_vector_to_list(vecs[0])) if vecs else 0
            return jsonify({
                "success": True,
                "message": f"本地 Embedding 测试成功，输出维度 {dim}",
                "dim": dim,
            })
        elif request_mode == "http_post":
            from rag_extract_split.infrastructure.embedding_http import embed_texts_http_post
            from rag_extract_split.infrastructure.embedding import _vector_to_list
            from requests.exceptions import RequestException as RequestsRequestException
            try:
                vecs = embed_texts_http_post(
                    ["测试文本", "hello world"], cfg, _vector_to_list
                )
                dim = len(vecs[0]) if vecs else 0
                return jsonify({
                    "success": True,
                    "message": f"HTTP Embedding 测试成功，输出维度 {dim}",
                    "dim": dim,
                })
            except RequestsRequestException as e:
                raise ConnectionError(f"无法连接到 HTTP Embedding 服务端点: {e}") from e
        else:
            import openai
            import httpx
            from rag_extract_split.common.helpers import set_temp_env_for_proxy, restore_env
            base_url = cfg.get("embedding_base_url")
            model = cfg.get("embedding_model_name")
            if not base_url or not model:
                return jsonify({"success": False, "message": "缺少 base_url 或 model"})
            old = set_temp_env_for_proxy(
                cfg.get("use_env_proxy", False),
                cfg.get("https_proxy", ""),
                cfg.get("http_proxy", ""),
                cfg.get("no_proxy", ""),
            )
            try:
                client = openai.OpenAI(
                    api_key=cfg.get("embedding_api_key") or "no-key",
                    base_url=base_url,
                    http_client=httpx.Client(timeout=cfg.get("timeout_sec", 120)),
                )
                resp = client.embeddings.create(model=model, input=["测试文本"])
                data = getattr(resp, "data", None) or []
                dim = len(data[0].embedding) if data else 0
                return jsonify({
                    "success": True,
                    "message": f"OpenAI 兼容 Embedding 测试成功，输出维度 {dim}",
                    "dim": dim,
                })
            finally:
                restore_env(old)
    except Exception as e:
        error_info = _format_embedding_test_error(e, cfg)
        return jsonify({
            "success": False,
            "message": error_info["message"],
            "error": {"type": error_info["type"], "detail": error_info["detail"]},
        }), 502


@llm_bp.route("/api/embedding/profiles/test-draft", methods=["POST"])
def test_embedding_profile_draft():
    """测试未保存的 Embedding 配置草稿。"""
    data = request.get_json()
    if not data:
        return jsonify({"success": False, "message": "缺少请求体"}), 400

    template_name = data.get("template")
    if not template_name:
        return jsonify({"success": False, "message": "请选择模板"}), 400

    manager = _get_embedding_manager()
    template = manager.template_manager.get_template(template_name)
    if template is None:
        return jsonify({"success": False, "message": f"模板不存在: {template_name}"}), 400

    try:
        profile_dict = _collect_profile_from_body(data, template_name)
    except ValueError as e:
        return jsonify({"success": False, "message": str(e)}), 400

    errors = template.validate(profile_dict)
    if errors:
        return jsonify({"success": False, "message": "表单校验失败", "errors": errors}), 400

    try:
        profile = manager.profile_manager.save_profile_dict(profile_dict)
        # 复用已保存配置的测试接口
        result = test_saved_embedding_profile(profile.id)
        # 注意：result 是 Response 对象；测试后删除临时草稿
        manager.profile_manager.delete_profile(profile.id)
        if isinstance(result, tuple):
            response_obj, status_code = result
            result_data = response_obj.get_json()
        else:
            result_data = result.get_json()
            status_code = result.status_code
        if not result_data.get("success", False):
            return jsonify({
                "success": False,
                "message": result_data.get("message") or "测试失败",
                "error": result_data.get("error"),
            }), status_code if status_code >= 400 else 502
        return result
    except Exception as e:
        error_info = _format_embedding_test_error(e, {})
        return jsonify({
            "success": False,
            "message": error_info["message"],
            "error": {"type": error_info["type"], "detail": error_info["detail"]},
        }), 500


# ============================================================
# Legacy-compatible Embedding Config API（供业务表单下拉框使用）
# ============================================================


@llm_bp.route("/api/embedding-configs", methods=["GET"])
def list_legacy_embedding_configs():
    """旧版风格 API：返回 {success, items, active}。"""
    items = list_embedding_configs()
    active = ""
    for it in items:
        if it.get("active"):
            active = it["name"]
            break
    return jsonify({"success": True, "items": items, "active": active})


@llm_bp.route("/api/embedding-configs/active", methods=["POST"])
def set_legacy_active_embedding_config():
    data = request.get_json() or {}
    name = str(data.get("name") or "").strip()
    if not name:
        return jsonify({"success": False, "message": "配置名称不能为空"}), 400
    try:
        set_active_embedding_config(name)
        return jsonify({"success": True, "message": f"已切换至配置 {name}"})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 400
