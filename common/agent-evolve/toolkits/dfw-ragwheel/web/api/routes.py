#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, Dict, List

from flask import Blueprint, jsonify, request, send_from_directory

from web.api.cli_executor import run_cli_command
from web.config import config

api_bp = Blueprint("api", __name__)


def _request_data() -> Dict[str, Any]:
    """统一获取表单或 JSON 参数。"""
    if request.is_json:
        return request.get_json(silent=True) or {}
    return request.form.to_dict() or {}


def _parse_json_field(value: str | None, default: Any = None) -> Any:
    """安全解析 JSON 字符串字段；若已是对象则直接返回。"""
    if value is None or value == "":
        return default
    if not isinstance(value, str):
        return value
    text = value.strip()
    if not text:
        return default
    try:
        return json.loads(text)
    except Exception as exc:
        raise ValueError(f"JSON 格式错误: {exc}")


def _save_upload_file(file_storage) -> Path:
    """保存上传文件到 web/uploads/，返回绝对路径。"""
    original_name = Path(file_storage.filename or "upload").name
    suffix = Path(original_name).suffix
    stored_name = f"{uuid.uuid4().hex}{suffix}"
    path = config.UPLOAD_DIR / stored_name
    file_storage.save(path)
    return path


def _resolve_file_path(file_path: str | None, file_key: str = "file") -> Path | None:
    """解析并校验文件路径，支持 Windows / Linux 路径。

    优先级：
    1. 从请求参数 file_path / path 解析
    2. 从上传文件 file_key 保存并返回
    """
    data = _request_data()

    # 1. 尝试从 path / file_path 参数解析
    raw_path = file_path or data.get("path") or data.get("file_path")
    if raw_path:
        path = Path(str(raw_path).strip())
        # 支持相对路径：以项目根目录为基准
        if not path.is_absolute():
            path = config.PROJECT_ROOT / path
        path = path.resolve()
        if path.is_file():
            return path

    # 2. 尝试从上传文件保存
    uploaded = request.files.get(file_key)
    if uploaded and uploaded.filename:
        return _save_upload_file(uploaded)

    return None


def _run_cli(args: list[str], timeout: int | None = None) -> Dict[str, Any]:
    """设置 DFW_RAG_HOME 并调用 CLI 执行器。"""
    env = {"DFW_RAG_HOME": str(config.PROJECT_ROOT)}
    return run_cli_command(
        args,
        cwd=config.PROJECT_ROOT,
        env=env,
        timeout=timeout or config.CLI_TIMEOUT,
    )


def _file_required_response() -> Dict[str, Any]:
    return {
        "success": False,
        "message": "文件不存在或路径为空，请上传文件或提供有效路径",
        "stdout": "",
        "stderr": "",
        "returncode": -1,
    }


@api_bp.route("/upload", methods=["POST"])
def upload() -> Any:
    """通用文件上传接口，保存到 web/uploads/。"""
    if "file" not in request.files:
        return jsonify(
            {
                "success": False,
                "message": "未提供文件字段（key=file）",
                "stdout": "",
                "stderr": "",
                "returncode": -1,
            }
        )
    file = request.files["file"]
    if not file or not file.filename:
        return jsonify(
            {
                "success": False,
                "message": "文件名为空",
                "stdout": "",
                "stderr": "",
                "returncode": -1,
            }
        )
    path = _save_upload_file(file)
    return jsonify(
        {
            "success": True,
            "message": "上传成功",
            "path": str(path),
            "filename": file.filename,
            "stdout": "",
            "stderr": "",
            "returncode": 0,
        }
    )


@api_bp.route("/outputs", methods=["GET"])
def list_outputs() -> Any:
    """列出 outputs/ 目录下的文件，供前端下拉框选择。"""
    outputs_dir = config.PROJECT_ROOT / "outputs"
    try:
        outputs_dir.mkdir(parents=True, exist_ok=True)
        files = []
        for path in outputs_dir.iterdir():
            if path.is_file():
                files.append({
                    "name": path.name,
                    "path": str(path.relative_to(config.PROJECT_ROOT)).replace("\\", "/"),
                    "size": path.stat().st_size,
                })
        files.sort(key=lambda x: x["name"])
        return jsonify({"success": True, "files": files})
    except Exception as exc:
        return jsonify({"success": False, "message": f"读取 outputs 目录失败：{exc}", "files": []})


@api_bp.route("/outputs/download/<path:filename>", methods=["GET"])
def download_output(filename: str) -> Any:
    """下载 outputs/ 目录下的结果文件。"""
    outputs_dir = config.PROJECT_ROOT / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    try:
        return send_from_directory(
            str(outputs_dir),
            filename,
            as_attachment=True,
        )
    except FileNotFoundError:
        return jsonify({"success": False, "message": "文件不存在"}), 404


@api_bp.route("/extract/import_base", methods=["POST"])
def extract_import_base() -> Any:
    """基础语料导入：dfw-rag import --input <file> --collection <collection> --replace"""
    data = _request_data()
    file_path = _resolve_file_path(None)
    collection = data.get("collection") or data.get("target_kb") or "default"
    replace = "replace" in data or data.get("replace") in ("on", "true", "1", True)

    if file_path is None:
        return jsonify(_file_required_response())

    args = ["import", "--input", str(file_path), "--collection", str(collection)]
    if replace:
        args.append("--replace")
    embedding_config_name = data.get("embedding_config_name")
    if embedding_config_name and str(embedding_config_name).strip():
        args.extend(["--embedding-config-name", str(embedding_config_name).strip()])

    result = _run_cli(args)
    result["message"] = "基础语料导入完成" if result["success"] else "基础语料导入失败"
    return jsonify(result)


@api_bp.route("/extract/run", methods=["POST"])
def extract_run() -> Any:
    """执行萃取：dfw-rag extract --excel <file> --target-kb <kb> --completion-mode <mode> --out <out>"""
    data = _request_data()
    file_path = _resolve_file_path(None)
    target_kb = data.get("target_kb") or data.get("target-kb") or "default"
    completion_mode = data.get("completion_mode") or data.get("completion-mode") or "cluster"
    # 统一输出到 outputs/ 目录，确保前端可通过 /api/outputs/download 下载
    outputs_dir = config.PROJECT_ROOT / "outputs"
    outputs_dir.mkdir(parents=True, exist_ok=True)
    raw_out = data.get("out")
    if raw_out:
        out_name = str(raw_out).strip()
        if out_name.startswith("outputs/"):
            out_name = out_name[len("outputs/"):]
        out = outputs_dir / out_name
    else:
        out = outputs_dir / f"extracted_{uuid.uuid4().hex[:8]}.xlsx"

    if file_path is None:
        return jsonify(_file_required_response())

    args = [
        "extract",
        "--excel",
        str(file_path),
        "--target-kb",
        str(target_kb),
        "--completion-mode",
        str(completion_mode),
        "--out",
        str(out),
    ]
    llm_config_name = data.get("llm_config_name")
    if llm_config_name and str(llm_config_name).strip():
        args.extend(["--llm-config-name", str(llm_config_name).strip()])
    embedding_config_name = data.get("embedding_config_name")
    if embedding_config_name and str(embedding_config_name).strip():
        args.extend(["--embedding-config-name", str(embedding_config_name).strip()])
    result = _run_cli(args)
    result["message"] = "知识萃取完成" if result["success"] else "知识萃取失败"
    # 显式返回输出路径，方便前端在 stdout 未解析到路径时仍可展示下载按钮
    result["output_path"] = str(out)
    return jsonify(result)


@api_bp.route("/extract/import_result", methods=["POST"])
def extract_import_result() -> Any:
    """萃取结果入库：dfw-rag import --input <xlsx> --collection <collection> [--replace] --export-ids <ids_file>"""
    data = _request_data()
    file_path = _resolve_file_path(None)
    collection = data.get("collection") or data.get("target_kb") or "default"
    replace = "replace" in data or data.get("replace") in ("on", "true", "1", True)

    ids_file = data.get("ids_file") or data.get("export_ids")
    if ids_file:
        ids_file = str(ids_file).strip()
        if not ids_file.startswith("outputs/"):
            ids_file = "outputs/" + ids_file
    else:
        ids_file = f"outputs/imported_ids_{uuid.uuid4().hex[:8]}.xlsx"

    if file_path is None:
        return jsonify(_file_required_response())

    args = [
        "import",
        "--input",
        str(file_path),
        "--collection",
        str(collection),
    ]
    if replace:
        args.append("--replace")
    args.extend(["--export-ids", str(ids_file)])
    embedding_config_name = data.get("embedding_config_name")
    if embedding_config_name and str(embedding_config_name).strip():
        args.extend(["--embedding-config-name", str(embedding_config_name).strip()])

    result = _run_cli(args)
    result["message"] = "萃取结果入库完成" if result["success"] else "萃取结果入库失败"
    # 显式返回新增数据ID文件路径，便于前端展示下载按钮
    result["output_path"] = str(Path(ids_file))
    return jsonify(result)


@api_bp.route("/extract/cleanup", methods=["POST"])
def extract_cleanup() -> Any:
    """语料清理：读取新增数据ID文件，按其中记录的 collection 与 id 删除已入库语料。

    流程：
    1. 从上传文件或 path 参数获取数据ID文件（xlsx/csv/json）。
    2. 若请求未提供 collection，则从文件中解析 collection 列/字段。
    3. 调用 dfw-rag delete --collection <c> --input <file> --id-column id
    """
    data = _request_data()
    file_path = _resolve_file_path(None)
    if file_path is None:
        return jsonify(_file_required_response())

    collection = data.get("collection", "").strip() or _read_collection_from_ids_file(file_path)
    if not collection:
        return jsonify({
            "success": False,
            "message": "未能从数据ID文件中解析出 collection，请显式填写或检查文件格式",
            "stdout": "",
            "stderr": "",
            "returncode": -1,
        })

    result = _run_cli([
        "delete",
        "--collection",
        str(collection),
        "--input",
        str(file_path),
        "--id-column",
        "id",
    ])
    result["message"] = "语料清理完成" if result["success"] else "语料清理失败"
    return jsonify(result)


def _read_collection_from_ids_file(path: Path) -> str | None:
    """从数据ID文件中读取 collection 信息。支持 xlsx/csv/json 格式。"""
    suffix = path.suffix.lower()
    try:
        if suffix == ".json":
            import json
            with open(path, "r", encoding="utf-8") as f:
                payload = json.load(f)
            return str(payload.get("collection") or "").strip() or None
        else:
            import pandas as pd
            if suffix == ".csv":
                df = pd.read_csv(path, dtype=str, encoding="utf-8-sig", engine="python")
            else:
                df = pd.read_excel(path, dtype=str, engine="openpyxl")
            lookup = {str(c).strip().lower(): c for c in df.columns.tolist()}
            col_name = lookup.get("collection")
            if col_name is None:
                return None
            vals = [str(v).strip() for v in df[col_name].dropna().unique() if str(v).strip()]
            return vals[0] if vals else None
    except Exception:
        return None


@api_bp.route("/verify/query", methods=["POST"])
def verify_query() -> Any:
    """召回验证：dfw-rag query --input-single <query> --collection <c> --top-k <k>
    或 dfw-rag query --input <file> --collection <c> --top-k <k> --output <out>
    """
    data = _request_data()
    collection = data.get("collection") or "default"
    top_k = data.get("top_k") or "3"
    input_single = data.get("input_single", "")
    output = data.get("output", "")

    if input_single:
        # 单条 query 模式
        args = [
            "query",
            "--input-single",
            str(input_single),
            "--collection",
            str(collection),
            "--top-k",
            str(top_k),
        ]
    else:
        # 批量文件模式
        file_path = _resolve_file_path(None)
        if file_path is None:
            return jsonify(_file_required_response())
        args = [
            "query",
            "--input",
            str(file_path),
            "--collection",
            str(collection),
            "--top-k",
            str(top_k),
        ]
        if output:
            out = str(output)
            if not out.startswith("outputs/"):
                out = "outputs/" + out
            args.extend(["--output", out])

    result = _run_cli(args)
    result["message"] = "召回验证完成" if result["success"] else "召回验证失败"
    return jsonify(result)


@api_bp.route("/synthesize/run", methods=["POST"])
def synthesize_run() -> Any:
    """知识合成：调用 single 子命令执行 LLM 相似问泛化 + embedding 筛选。"""
    data = _request_data()
    file_path = _resolve_file_path(None)

    output = data.get("output") or data.get("out")
    if output:
        output = str(output).strip()
        if not output.startswith("outputs/"):
            output = "outputs/" + output
    else:
        output = f"outputs/synthesized_{uuid.uuid4().hex[:8]}.csv"

    if file_path is None:
        return jsonify(_file_required_response())

    args: list[str] = [
        "single",
        "--single-input",
        str(file_path),
        "--single-output",
        str(output),
    ]
    if data.get("single_sheet"):
        args.extend(["--single-sheet", str(data["single_sheet"])])
    if data.get("single_k"):
        args.extend(["--single-k", str(data["single_k"])])
    if data.get("single_m"):
        args.extend(["--single-m", str(data["single_m"])])
    if data.get("single_threshold"):
        args.extend(["--single-threshold", str(data["single_threshold"])])
    if data.get("single_max_attempts"):
        args.extend(["--single-max-attempts", str(data["single_max_attempts"])])
    if data.get("single_q_column"):
        args.extend(["--single-q-column", str(data["single_q_column"])])
    if data.get("single_a_column"):
        args.extend(["--single-a-column", str(data["single_a_column"])])
    if data.get("single_llm_trace_file"):
        args.extend(["--single-llm-trace-file", str(data["single_llm_trace_file"])])

    llm_config_name = data.get("llm_config_name")
    if llm_config_name and str(llm_config_name).strip():
        args.extend(["--llm-config-name", str(llm_config_name).strip()])

    result = _run_cli(args)
    result["message"] = "知识合成完成" if result["success"] else "知识合成失败"
    # 显式返回输出路径，便于前端展示下载按钮
    result["output_path"] = str(Path(output))
    return jsonify(result)


@api_bp.route("/collections", methods=["GET"])
def list_collections() -> Any:
    """列出 collections、数量及写入时的 embedding 配置信息。"""
    try:
        from rag_extract_split.infrastructure.chroma_store import get_chroma_client
        client = get_chroma_client()
        coll_infos = client.list_collections()
        collections = []
        for c in coll_infos:
            name = getattr(c, "name", str(c))
            try:
                col = client.get_collection(name=name)
                count = col.count()
                meta = col.metadata or {}
            except Exception:
                count = None
                meta = {}
            collections.append({
                "name": name,
                "count": count,
                "embedding_config": meta.get("embedding_config") or "",
                "embedding_model": meta.get("embedding_model") or "",
            })
        return jsonify({
            "success": True,
            "collections": collections,
            "message": "获取 collections 完成",
            "stdout": "",
            "stderr": "",
            "returncode": 0,
        })
    except Exception as exc:
        return jsonify({
            "success": False,
            "message": f"获取 collections 失败：{exc}",
            "collections": [],
            "stdout": "",
            "stderr": str(exc),
            "returncode": -1,
        })


@api_bp.route("/collections/<collection_name>", methods=["DELETE", "POST"])
def delete_collection(collection_name: str) -> Any:
    """删除整个 collection。支持 DELETE 和 POST 两种方式，便于前端兼容。"""
    return _do_delete_collection(collection_name)


@api_bp.route("/collections/<collection_name>/delete", methods=["POST"])
def delete_collection_post_alias(collection_name: str) -> Any:
    """删除 collection 的 POST 别名端点，避免某些环境对 DELETE/参数路由支持不佳。"""
    return _do_delete_collection(collection_name)


def _do_delete_collection(collection_name: str) -> Any:
    """执行 collection 删除的公共逻辑，与 CLI `dfw-rag delete --collection <name> -y` 保持一致。"""
    collection_name = collection_name.strip() if collection_name else ""
    if not collection_name:
        return jsonify({
            "success": False,
            "message": "Collection 名称不能为空",
            "stdout": "",
            "stderr": "",
            "returncode": -1,
        })

    try:
        # 与 CLI 完全一致的导入路径和调用方式
        from rag_extract_split.infrastructure.chroma_store import drop_collection
        drop_collection(collection_name)
        return jsonify({
            "success": True,
            "message": f"Collection '{collection_name}' 已删除",
            "stdout": "",
            "stderr": "",
            "returncode": 0,
        })
    except Exception as exc:
        return jsonify({
            "success": False,
            "message": f"删除 Collection 失败：{exc}",
            "stdout": "",
            "stderr": str(exc),
            "returncode": -1,
        })


# 知识质检路由由 qc 模块代理后续完善；此处做占位注册，文件缺失时静默跳过。
try:
    import sys
    from pathlib import Path

    # 确保 web/backend 在模块搜索路径中，使 qc_routes 能正确导入 backend.knowledge_qc
    _web_backend_dir = Path(__file__).resolve().parent.parent / "backend"
    if str(_web_backend_dir) not in sys.path:
        sys.path.insert(0, str(_web_backend_dir))

    from web.api.qc_routes import qc_bp  # type: ignore[import-not-found]

    api_bp.register_blueprint(qc_bp)
except ImportError:
    pass
