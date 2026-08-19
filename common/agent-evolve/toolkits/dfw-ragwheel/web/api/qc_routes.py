#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RAG 知识质检 API 路由。"""

from __future__ import annotations

import json
import logging
import os
import shutil
import sys
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from flask import Blueprint, jsonify, render_template, request, send_file
from werkzeug.utils import secure_filename

# 将 web/ 目录加入模块搜索路径，从而保持 dfw-ragwheel 中
# `from backend.knowledge_qc...` 的原始导入路径不变。
_WEB_DIR = Path(__file__).resolve().parents[1]
if str(_WEB_DIR) not in sys.path:
    sys.path.append(str(_WEB_DIR))

from backend.knowledge_qc.qc_bridge import (  # noqa: E402
    get_paths,
    load_kqc_settings,
    load_wordlist_text,
    merge_rules_payload,
    read_env_file_dict,
    read_rules_yaml_text,
    rules_to_form_dict,
)
from rag_extract_split.config.llmkit_manager import list_config_names  # noqa: E402
from rag_extract_split.config.embedding_manager import list_embedding_configs  # noqa: E402

logger = logging.getLogger(__name__)

qc_bp = Blueprint("qc", __name__)

_KQC_JOBS: Dict[str, Dict[str, Any]] = {}
_KQC_LOCK = threading.Lock()
_MAX_LOG_LINES = 800
_KQC_MAX_JOBS = 48
_QC_TERMINAL_TTL_SEC = 86400

# 当前活跃的 QC 任务（用于刷新后恢复）
_ACTIVE_QC_JOB: Optional[Dict[str, Any]] = None


@dataclass
class QcJobArgs:
    job_id: str
    input_path: Path
    output_dir: Path
    rules_dict: Dict[str, Any]
    task: str
    env_override: Optional[Dict[str, Any]] = None
    wordlists_override: Optional[Dict[str, str]] = None
    llm_config_name: Optional[str] = None
    embedding_config_name: Optional[str] = None
    intent_filter: Optional[Dict[str, Any]] = None


@dataclass
class RetryJobArgs:
    job_id: str
    source_path: Path
    output_dir: Path
    task: str
    prior_result: Dict[str, Any] = field(default_factory=dict)
    qc_config: Dict[str, Any] = field(default_factory=dict)


def _purge_kqc_jobs() -> None:
    now = time.time()
    with _KQC_LOCK:
        for jid in list(_KQC_JOBS.keys()):
            job = _KQC_JOBS.get(jid) or {}
            if job.get("status") not in ("completed", "failed", "cancelled"):
                continue
            t0 = float(job.get("_terminal_at") or job.get("_created_at") or now)
            if now - t0 <= _QC_TERMINAL_TTL_SEC:
                continue
            raw = job.get("_temp_output_dir")
            if raw:
                _remove_temp_dir(Path(raw))
            _KQC_JOBS.pop(jid, None)

        if len(_KQC_JOBS) <= _KQC_MAX_JOBS:
            return
        terminals = [
            (jid, job)
            for jid, job in _KQC_JOBS.items()
            if job.get("status") in ("completed", "failed", "cancelled")
        ]
        terminals.sort(
            key=lambda item: float(
                item[1].get("_terminal_at") or item[1].get("_created_at") or 0.0
            )
        )
        while len(_KQC_JOBS) > _KQC_MAX_JOBS and terminals:
            jid, job = terminals.pop(0)
            raw = job.get("_temp_output_dir")
            if raw:
                _remove_temp_dir(Path(raw))
            _KQC_JOBS.pop(jid, None)


def _new_job(kind: str) -> str:
    _purge_kqc_jobs()
    job_id = uuid.uuid4().hex
    with _KQC_LOCK:
        _KQC_JOBS[job_id] = {
            "kind": kind,
            "status": "running",
            "progress": {"current": 0, "total": 1, "label": ""},
            "logs": [],
            "error": None,
            "result": {},
            "cancel_requested": False,
            "_created_at": time.time(),
        }
    return job_id


def _job_cancel_requested(job_id: str) -> bool:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        return bool(job and job.get("cancel_requested"))


def _job_update(job_id: str, **kwargs) -> None:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        if not job:
            return
        if kwargs.get("status") in ("completed", "failed", "cancelled"):
            kwargs.setdefault("_terminal_at", time.time())
        job.update(kwargs)


def _job_log(job_id: str, msg: str) -> None:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        if not job:
            return
        logs: List[str] = job.setdefault("logs", [])
        logs.append(str(msg))
        if len(logs) > _MAX_LOG_LINES:
            del logs[: len(logs) - _MAX_LOG_LINES]


def _enrich_qc_result(result: Dict[str, Any]) -> Dict[str, Any]:
    """为质检任务结果补充基于 JSON 报告的可续检异常行数。"""
    from backend.knowledge_qc.report.exporter import count_retryable_error_rows

    enriched = dict(result or {})
    enriched["retryable_errors"] = count_retryable_error_rows(enriched)
    return enriched


def _job_snapshot(job_id: str, log_from: int = 0) -> Optional[Dict[str, Any]]:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        if not job:
            return None
        all_logs = list(job.get("logs") or [])
        offset = max(0, min(int(log_from), len(all_logs)))
        result = dict(job.get("result") or {})
        if job.get("kind") == "qc" and job.get("status") in (
            "completed",
            "cancelled",
            "failed",
        ):
            result = _enrich_qc_result(result)
        return {
            "kind": job.get("kind"),
            "status": job.get("status"),
            "progress": dict(job.get("progress") or {}),
            "logs": all_logs[offset:],
            "log_offset": len(all_logs),
            "error": job.get("error"),
            "result": result,
        }


def _temp_uploaded_excel(file_storage) -> Path:
    fname = secure_filename(file_storage.filename or "upload.xlsx")
    suffix = Path(fname).suffix.lower()
    if suffix not in (".xlsx", ".xls"):
        suffix = ".xlsx"
    fd, path = tempfile.mkstemp(suffix=suffix, prefix="kqc_upload_")
    os.close(fd)
    dest = Path(path)
    file_storage.save(str(dest))
    return dest


def _remove_temp_upload(path: Path) -> None:
    try:
        if path.is_file():
            path.unlink()
    except OSError:
        pass


def _temp_job_output_dir() -> Path:
    return Path(tempfile.mkdtemp(prefix="kqc_out_"))


def _remove_temp_dir(path: Optional[Path]) -> None:
    if not path:
        return
    try:
        shutil.rmtree(path, ignore_errors=True)
    except OSError:
        pass


def _set_job_temp_output_dir(job_id: str, output_dir: Path) -> None:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        if job is not None:
            job["_temp_output_dir"] = str(output_dir)


def _cleanup_job_temp_output(job_id: str) -> None:
    with _KQC_LOCK:
        job = _KQC_JOBS.get(job_id)
        raw = job.pop("_temp_output_dir", None) if job else None
    if raw:
        _remove_temp_dir(Path(raw))


# ── 页面与配置接口 ───────────────────────────────────────────


@qc_bp.route("/qc", methods=["GET"])
def rag_knowledge_qc_page():
    """知识质检页面入口。"""
    return render_template("qc.html")


@qc_bp.route("/qc/rules", methods=["GET"])
def rag_knowledge_qc_get_rules():
    try:
        settings = load_kqc_settings()
        rules = settings["rules"]
        return jsonify(
            {
                "success": True,
                "form": rules_to_form_dict(rules),
                "yaml": read_rules_yaml_text(),
            }
        )
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/wordlists", methods=["GET"])
def rag_knowledge_qc_get_wordlists():
    try:
        return jsonify(
            {
                "success": True,
                "regulatory": load_wordlist_text("regulatory"),
                "prohibited": load_wordlist_text("prohibited"),
            }
        )
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/env", methods=["GET"])
def rag_knowledge_qc_get_env():
    try:
        llm_configs = []
        try:
            llm_configs = list_config_names()
        except Exception:
            logger.warning("加载 LLM 配置名称失败", exc_info=True)
        embedding_configs = []
        try:
            embedding_configs = list_embedding_configs()
        except Exception:
            logger.warning("加载 Embedding 配置失败", exc_info=True)
        return jsonify(
            {
                "success": True,
                "env": read_env_file_dict(),
                "llm_configs": llm_configs,
                "embedding_configs": embedding_configs,
            }
        )
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/config_paths", methods=["GET"])
def rag_knowledge_qc_config_paths():
    try:
        p = get_paths()

        def rel(path: Path) -> str:
            return str(path.relative_to(p["repo_root"])).replace("\\", "/")
        return jsonify(
            {
                "success": True,
                "paths": {
                    "rules_file": rel(p["rules_path"]),
                    "env_file": rel(p["env_path"]),
                    "wordlists_dir": rel(p["wordlists_dir"]),
                    "chroma_dir": rel(p["chroma_dir"]),
                },
            }
        )
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ── 质检任务 ─────────────────────────────────────────────────


@qc_bp.route("/qc/active_qc", methods=["GET"])
def rag_knowledge_qc_active_qc():
    """返回当前活跃（运行中）的 QC 任务，供前端刷新后恢复。"""
    global _ACTIVE_QC_JOB
    try:
        with _KQC_LOCK:
            job = None
            if _ACTIVE_QC_JOB:
                job = _KQC_JOBS.get(_ACTIVE_QC_JOB.get("job_id"))
            if job and job.get("kind") == "qc" and job.get("status") == "running":
                return jsonify(
                    {
                        "success": True,
                        "has_job": True,
                        "job_id": _ACTIVE_QC_JOB["job_id"],
                        "source_filename": _ACTIVE_QC_JOB.get("source_filename", ""),
                        "task": _ACTIVE_QC_JOB.get("task", "question"),
                    }
                )
        return jsonify({"success": True, "has_job": False})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/run", methods=["POST"])
def rag_knowledge_qc_run():
    global _ACTIVE_QC_JOB
    try:
        if "file" not in request.files:
            return jsonify({"success": False, "error": "请上传 Excel 文件"}), 400
        f = request.files["file"]
        if not f or not f.filename:
            return jsonify({"success": False, "error": "未选择文件"}), 400

        cfg_raw = request.form.get("config") or "{}"
        try:
            cfg = json.loads(cfg_raw)
        except json.JSONDecodeError:
            return jsonify({"success": False, "error": "config 不是合法 JSON"}), 400

        if not cfg.get("detection_mode", {}).get("batch") and not cfg.get(
            "detection_mode", {}
        ).get("production"):
            return jsonify({"success": False, "error": "请至少选择一种检测模式"}), 400

        # 如果已有运行中的 QC 任务，则接续（单用户场景）
        with _KQC_LOCK:
            if _ACTIVE_QC_JOB:
                active = _KQC_JOBS.get(_ACTIVE_QC_JOB.get("job_id"))
                if active and active.get("kind") == "qc" and active.get("status") == "running":
                    return jsonify(
                        {
                            "success": True,
                            "job_id": _ACTIVE_QC_JOB["job_id"],
                            "resumed": True,
                        }
                    )

        input_path = _temp_uploaded_excel(f)
        output_dir = _temp_job_output_dir()
        task = (cfg.get("task") or "question").strip()
        rules_dict = merge_rules_payload(load_kqc_settings()["rules"], cfg)
        # 意图描述质检：将前端的 intent_llm 开关映射到规则中
        if task == 'intent':
            checkers = rules_dict.setdefault("checkers", {})
            checkers["intent_llm"] = bool(cfg.get("checkers", {}).get("intent_llm", False))
        env_override = cfg.get("env") if isinstance(cfg.get("env"), dict) else None
        wordlists_override = (
            cfg.get("wordlists") if isinstance(cfg.get("wordlists"), dict) else None
        )
        intent_filter = cfg.get("intent_filter") if isinstance(cfg.get("intent_filter"), dict) else None

        job_id = _new_job("qc")
        _set_job_temp_output_dir(job_id, output_dir)
        llm_config_name = (cfg.get("llm_config_name") or "").strip() or None
        embedding_config_name = (cfg.get("embedding_config_name") or "").strip() or None
        with _KQC_LOCK:
            _ACTIVE_QC_JOB = {
                "job_id": job_id,
                "source_filename": f.filename,
                "task": task,
            }
            _KQC_JOBS[job_id]["_qc_config"] = {
                "task": task,
                "rules_dict": rules_dict,
                "env_override": env_override,
                "wordlists_override": wordlists_override,
                "llm_config_name": llm_config_name,
                "embedding_config_name": embedding_config_name,
                "intent_filter": intent_filter,
            }
        threading.Thread(
            target=_run_qc_thread,
            args=(
                QcJobArgs(
                    job_id=job_id,
                    input_path=input_path,
                    output_dir=output_dir,
                    rules_dict=rules_dict,
                    task=task,
                    env_override=env_override,
                    wordlists_override=wordlists_override,
                    llm_config_name=llm_config_name,
                    embedding_config_name=embedding_config_name,
                    intent_filter=intent_filter,
                ),
            ),
            daemon=True,
        ).start()
        return jsonify({"success": True, "job_id": job_id})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def _run_qc_thread(args: QcJobArgs) -> None:
    job_id = args.job_id
    input_path = args.input_path
    try:
        _run_qc_thread_impl(args)
    except Exception as e:  # noqa: BLE001
        _job_log(job_id, f"质检异常: {e}")
        _job_update(job_id, status="failed", error=str(e))
    finally:
        _remove_temp_upload(input_path)
        snap = _job_snapshot(job_id)
        if snap and snap.get("status") == "failed":
            _cleanup_job_temp_output(job_id)
        global _ACTIVE_QC_JOB
        with _KQC_LOCK:
            if _ACTIVE_QC_JOB and _ACTIVE_QC_JOB.get("job_id") == job_id:
                _ACTIVE_QC_JOB = None


def _run_qc_thread_impl(args: QcJobArgs) -> None:
    job_id = args.job_id
    input_path = args.input_path
    output_dir = args.output_dir
    rules_dict = args.rules_dict
    task = args.task
    env_override = args.env_override
    wordlists_override = args.wordlists_override
    llm_config_name = args.llm_config_name
    embedding_config_name = args.embedding_config_name
    intent_filter = args.intent_filter
    from backend.knowledge_qc.loaders.excel_loader import (
        QcExcelResults,
        load_intent_sheet,
        load_question_sheet,
        write_qc_excel,
    )
    from backend.knowledge_qc.models import QCTaskType
    from backend.knowledge_qc.pipeline.intent_orchestrator import (
        IntentQualityPipeline,
        IntentRunOpts,
    )
    from backend.knowledge_qc.pipeline.orchestrator import QualityPipeline, QuestionRunOpts
    from backend.knowledge_qc.report.checkpoint import (
        QuestionCheckpointArgs,
        checkpoint_interval_from_rules,
        intent_results_map,
        question_results_map,
        write_intent_checkpoint,
        write_question_checkpoint,
    )
    from backend.knowledge_qc.report.exporter import (
        count_retryable_error_rows,
        export_csv,
        export_intent_json,
        export_json,
        print_intent_summary,
        print_summary,
        timestamped_report_paths,
    )

    settings = load_kqc_settings(
        rules_dict,
        env_override=env_override,
        wordlists_override=wordlists_override,
        llm_config_name=llm_config_name,
        embedding_config_name=embedding_config_name,
    )
    rules = settings["rules"]
    id_cfg = rules.get("id", {})
    checkpoint_interval = checkpoint_interval_from_rules(rules)

    def on_progress(cur, total, label):
        _job_update(
            job_id,
            progress={"current": int(cur), "total": int(total), "label": str(label)},
        )

    def on_log(msg: str):
        _job_log(job_id, msg)

    def should_cancel() -> bool:
        return _job_cancel_requested(job_id)

    _job_log(job_id, "正在初始化（向量库、Embedding API）…")
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    excel_out = output_dir / f"qc_result_{ts}.xlsx"

    # 保存源文件副本到输出目录，供续检使用
    source_copy = output_dir / f"source_{ts}.xlsx"
    try:
        shutil.copy2(str(input_path), str(source_copy))
    except Exception:
        source_copy = input_path

    if task == QCTaskType.INTENT.value:
        pipeline = IntentQualityPipeline(settings)
        _job_log(job_id, "初始化完成")
        records = load_intent_sheet(input_path, rules, id_cfg)
        _job_log(job_id, f"已加载 {len(records)} 条意图描述，开始检测…")
        if checkpoint_interval > 0:
            _job_log(
                job_id,
                f"增量写入间隔：每 {checkpoint_interval} 条 → {excel_out.name}",
            )

        def flush_intent(report):
            write_intent_checkpoint(input_path, excel_out, rules, report)

        report = pipeline.run_records(
            records,
            IntentRunOpts(
                on_progress=on_progress,
                on_log=on_log,
                checkpoint_interval=checkpoint_interval,
                on_checkpoint=flush_intent if checkpoint_interval > 0 else None,
                should_cancel=should_cancel,
                intent_filter=intent_filter,
            ),
        )
        jp, _cp = timestamped_report_paths(output_dir)
        write_qc_excel(
            input_path,
            excel_out,
            rules,
            "intent",
            QcExcelResults(intent_results=intent_results_map(report.results, rules)),
        )
        export_intent_json(report, jp)
        summary = print_intent_summary(report)
        cancelled = should_cancel()
        if cancelled:
            _job_log(job_id, f"用户已停止质检（已完成 {report.total} 条）")
        _job_update(
            job_id,
            status="cancelled" if cancelled else "completed",
            result={
                "summary": summary,
                "excel_path": str(excel_out),
                "excel_name": excel_out.name,
                "json_path": str(jp),
                "json_name": jp.name,
                "passed": report.passed,
                "failed": report.failed,
                "errors": getattr(report, "errors", 0),
                "total": report.total,
                "source_path": str(source_copy),
                "task": task,
            },
        )
        return

    pipeline = QualityPipeline(settings)
    _job_log(job_id, "初始化完成")
    records, _ = load_question_sheet(input_path, rules, id_cfg)
    _job_log(job_id, f"已加载 {len(records)} 条相似问，开始检测…")
    jp, cp = timestamped_report_paths(output_dir)
    if checkpoint_interval > 0:
        _job_log(
            job_id,
            f"增量写入间隔：每 {checkpoint_interval} 条 → "
            f"{excel_out.name} / {jp.name} / {cp.name}",
        )

    def flush_question(report):
        write_question_checkpoint(
            input_path,
            QuestionCheckpointArgs(
                excel_out=excel_out,
                json_path=jp,
                csv_path=cp,
                rules=rules,
                report=report,
            ),
        )

    report = pipeline.run_records(
        records,
        QuestionRunOpts(
            on_progress=on_progress,
            on_log=on_log,
            checkpoint_interval=checkpoint_interval,
            on_checkpoint=flush_question if checkpoint_interval > 0 else None,
            should_cancel=should_cancel,
        ),
    )

    write_qc_excel(
        input_path,
        excel_out,
        rules,
        "question",
        QcExcelResults(question_results=question_results_map(report.results, rules)),
    )
    _job_log(job_id, "正在写入报告…")
    export_json(report, jp)
    export_csv(report, cp, rules=rules)
    summary = print_summary(report)
    cancelled = should_cancel()
    if cancelled:
        _job_log(job_id, f"用户已停止质检（已完成 {report.total} 条）")
    _job_update(
        job_id,
        status="cancelled" if cancelled else "completed",
        result={
            "summary": summary,
            "excel_path": str(excel_out),
            "excel_name": excel_out.name,
            "json_path": str(jp),
            "json_name": jp.name,
            "csv_path": str(cp),
            "csv_name": cp.name,
            "passed": report.passed,
            "failed": report.failed,
            "errors": getattr(report, "errors", 0),
            "total": report.total,
            "source_path": str(source_copy),
            "task": task,
        },
    )


@qc_bp.route("/qc/retry_errors", methods=["POST"])
def rag_knowledge_qc_retry_errors():
    """继续质检已完成任务中 verdict 为「质检异常」的行。"""
    try:
        data = request.get_json(silent=True) or {}
        job_id = (data.get("job_id") or "").strip()
        if not job_id:
            return jsonify({"success": False, "error": "缺少 job_id"}), 400
        with _KQC_LOCK:
            job = _KQC_JOBS.get(job_id)
            if not job:
                return jsonify({"success": False, "error": "任务不存在或已过期"}), 404
            if job.get("kind") != "qc":
                return jsonify({"success": False, "error": "该任务不支持续检"}), 400
            if job.get("status") == "running":
                return jsonify({"success": False, "error": "任务仍在运行中"}), 400
            prior_result = dict(job.get("result") or {})
            qc_config = dict(job.get("_qc_config") or {})

        source_path = Path(str(prior_result.get("source_path") or ""))
        if not source_path.is_file():
            return jsonify({"success": False, "error": "原始 Excel 已失效，请重新上传并质检"}), 400

        task = str(prior_result.get("task") or qc_config.get("task") or "question").strip()
        report_json = Path(str(prior_result.get("json_path") or ""))
        if task == "question" and not report_json.is_file():
            return jsonify({"success": False, "error": "质检报告已失效，请重新上传并质检"}), 400

        from backend.knowledge_qc.report.exporter import count_retryable_error_rows

        retryable = count_retryable_error_rows(prior_result)
        if retryable <= 0:
            return jsonify({"success": False, "error": "没有可重检的异常行"}), 400

        output_dir = source_path.parent
        threading.Thread(
            target=_run_retry_errors_thread,
            args=(
                RetryJobArgs(
                    job_id=job_id,
                    source_path=source_path,
                    output_dir=output_dir,
                    task=task,
                    prior_result=prior_result,
                    qc_config=qc_config,
                ),
            ),
            daemon=True,
        ).start()
        return jsonify({"success": True, "job_id": job_id})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def _run_retry_errors_thread(args: RetryJobArgs) -> None:
    job_id = args.job_id
    prior_result = args.prior_result
    try:
        _job_update(
            job_id,
            status="running",
            progress={"current": 0, "total": 1, "label": ""},
            error=None,
        )
        _run_retry_errors_thread_impl(args)
    except Exception as e:
        _job_log(job_id, f"续检异常: {e}")
        msg = str(e)
        if "没有可重检的异常行" in msg:
            restored = _enrich_qc_result(dict(prior_result))
            restored["retryable_errors"] = 0
            _job_update(job_id, status="completed", result=restored, error=None)
        else:
            _job_update(job_id, status="failed", error=msg)


def _run_retry_errors_thread_impl(args: RetryJobArgs) -> None:
    job_id = args.job_id
    source_path = args.source_path
    output_dir = args.output_dir
    task = args.task
    prior_result = args.prior_result
    qc_config = args.qc_config
    from backend.knowledge_qc.loaders.excel_loader import (
        QcExcelResults,
        load_intent_sheet,
        load_question_sheet,
        write_qc_excel,
    )
    from backend.knowledge_qc.models import QCTaskType
    from backend.knowledge_qc.pipeline.intent_orchestrator import (
        IntentQualityPipeline,
        IntentRunOpts,
    )
    from backend.knowledge_qc.pipeline.orchestrator import QualityPipeline, QuestionRunOpts
    from backend.knowledge_qc.report.checkpoint import (
        intent_results_map,
        question_results_map,
    )
    from backend.knowledge_qc.report.exporter import (
        error_row_indices_intent,
        error_row_indices_question,
        export_csv,
        export_intent_json,
        export_json,
        load_intent_report_json,
        load_question_report_json,
        merge_intent_reports,
        merge_question_reports,
        print_intent_summary,
        print_summary,
        validate_retry_coverage,
    )

    settings = load_kqc_settings(
        qc_config.get("rules_dict"),
        env_override=qc_config.get("env_override"),
        wordlists_override=qc_config.get("wordlists_override"),
        llm_config_name=qc_config.get("llm_config_name"),
        embedding_config_name=qc_config.get("embedding_config_name"),
    )
    rules = settings["rules"]
    id_cfg = rules.get("id", {})

    def on_progress(cur, total, label):
        _job_update(
            job_id,
            progress={"current": int(cur), "total": int(total), "label": str(label)},
        )

    def on_log(msg: str):
        _job_log(job_id, msg)

    def should_cancel() -> bool:
        return _job_cancel_requested(job_id)

    _job_log(job_id, "续检初始化（复用向量库，仅重试 LLM 相关检测）…")

    if task == QCTaskType.INTENT.value:
        report_json = Path(str(prior_result.get("json_path") or ""))
        prior_report = load_intent_report_json(report_json) if report_json.is_file() else {"results": []}
        error_rows = set(error_row_indices_intent(prior_report))
        if not error_rows:
            raise ValueError("没有可重检的异常行")
        records = load_intent_sheet(source_path, rules, id_cfg)
        record_rows = {r.row_index for r in records}
        missing_in_source = error_rows - record_rows
        if missing_in_source:
            rows = ", ".join(str(x) for x in sorted(missing_in_source))
            raise ValueError(f"源文件中找不到异常行（行号 {rows}）")
        pipeline = IntentQualityPipeline(settings)
        _job_log(job_id, f"共 {len(error_rows)} 条异常意图描述待重检（行号 {', '.join(str(x) for x in sorted(error_rows))}）…")
        retry_report = pipeline.run_records(
            records,
            IntentRunOpts(
                on_progress=on_progress,
                on_log=on_log,
                should_cancel=should_cancel,
                row_indices=error_rows,
                intent_filter=qc_config.get("intent_filter"),
                retry_mode=True,
            ),
        )
        validate_retry_coverage(
            error_rows,
            retry_report.results,
            row_getter=lambda r: r.record.row_index,
        )
        merged_report = merge_intent_reports(prior_report, retry_report)
        ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        fallback_excel = output_dir / f"qc_result_{ts}.xlsx"
        excel_out = Path(str(prior_result.get("excel_path") or fallback_excel))
        write_qc_excel(
            source_path,
            excel_out,
            rules,
            "intent",
            QcExcelResults(
                intent_results=intent_results_map(merged_report.results, rules),
            ),
        )
        if report_json.is_file():
            export_intent_json(merged_report, report_json)
        summary = print_intent_summary(merged_report)
        cancelled = should_cancel()
        _job_update(
            job_id,
            status="cancelled" if cancelled else "completed",
            result={
                "summary": summary,
                "excel_path": str(excel_out),
                "excel_name": excel_out.name,
                "json_path": str(report_json) if report_json else "",
                "json_name": report_json.name if report_json else "",
                "passed": merged_report.passed,
                "failed": merged_report.failed,
                "errors": getattr(merged_report, "errors", 0),
                "total": merged_report.total,
                "source_path": str(source_path),
                "task": task,
            },
        )
        return

    report_json = Path(str(prior_result.get("json_path") or ""))
    if not report_json.is_file():
        raise ValueError("质检报告已失效，请重新上传并质检")
    prior_report = load_question_report_json(report_json)
    error_rows = set(error_row_indices_question(prior_report))
    if not error_rows:
        raise ValueError("没有可重检的异常行")
    prior_hits = dict(prior_report.get("similarity_hits_by_record_id") or {})
    records, _ = load_question_sheet(source_path, rules, id_cfg)
    record_rows = {r.row_index for r in records}
    missing_in_source = error_rows - record_rows
    if missing_in_source:
        rows = ", ".join(str(x) for x in sorted(missing_in_source))
        raise ValueError(f"源文件中找不到异常行（行号 {rows}）")
    pipeline = QualityPipeline(settings)
    _job_log(job_id, f"共 {len(error_rows)} 条异常相似问待重检（行号 {', '.join(str(x) for x in sorted(error_rows))}）…")
    retry_report = pipeline.run_records(
        records,
        QuestionRunOpts(
            on_progress=on_progress,
            on_log=on_log,
            should_cancel=should_cancel,
            row_indices=error_rows,
            retry_mode=True,
            similarity_hits_by_record_id=prior_hits,
        ),
    )
    validate_retry_coverage(
        error_rows,
        retry_report.results,
        row_getter=lambda r: r.record.row_index,
    )
    merged_report = merge_question_reports(prior_report, retry_report)
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    fallback_excel = output_dir / f"qc_result_{ts}.xlsx"
    fallback_csv = output_dir / f"report_{ts}.csv"
    excel_out = Path(str(prior_result.get("excel_path") or fallback_excel))
    cp = Path(str(prior_result.get("csv_path") or fallback_csv))
    write_qc_excel(
        source_path,
        excel_out,
        rules,
        "question",
        QcExcelResults(
            question_results=question_results_map(merged_report.results, rules),
        ),
    )
    export_json(merged_report, report_json)
    export_csv(merged_report, cp, rules=rules)
    summary = print_summary(merged_report)
    cancelled = should_cancel()
    _job_update(
        job_id,
        status="cancelled" if cancelled else "completed",
        result={
            "summary": summary,
            "excel_path": str(excel_out),
            "excel_name": excel_out.name,
            "json_path": str(report_json),
            "json_name": report_json.name,
            "csv_path": str(cp),
            "csv_name": cp.name,
            "passed": merged_report.passed,
            "failed": merged_report.failed,
            "errors": getattr(merged_report, "errors", 0),
            "total": merged_report.total,
            "source_path": str(source_path),
            "task": task,
        },
    )


@qc_bp.route("/qc/cancel", methods=["POST"])
def rag_knowledge_qc_cancel():
    try:
        data = request.get_json(silent=True) or {}
        job_id = (data.get("job_id") or "").strip()
        if not job_id:
            return jsonify({"success": False, "error": "缺少 job_id"}), 400
        with _KQC_LOCK:
            job = _KQC_JOBS.get(job_id)
            if not job:
                return jsonify({"success": False, "error": "任务不存在或已过期"}), 404
            if job.get("kind") != "qc":
                return jsonify({"success": False, "error": "该任务不支持停止"}), 400
            if job.get("status") != "running":
                return jsonify({"success": False, "error": "任务未在运行中"}), 400
            job["cancel_requested"] = True
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/job", methods=["GET"])
def rag_knowledge_qc_job():
    job_id = (request.args.get("job_id") or "").strip()
    if not job_id:
        return jsonify({"success": False, "error": "缺少 job_id"}), 400
    try:
        log_from = int(request.args.get("log_from") or 0)
    except (TypeError, ValueError):
        log_from = 0
    _purge_kqc_jobs()
    snap = _job_snapshot(job_id, log_from=log_from)
    if not snap:
        return jsonify({"success": False, "error": "任务不存在或已过期"}), 404
    return jsonify({"success": True, **snap})


@qc_bp.route("/qc/download", methods=["GET"])
def rag_knowledge_qc_download():
    job_id = (request.args.get("job_id") or "").strip()
    kind = (request.args.get("type") or "excel").strip().lower()
    snap = _job_snapshot(job_id)
    if not snap or snap.get("status") not in ("completed", "cancelled"):
        return jsonify({"success": False, "error": "任务未完成或不存在"}), 404
    result = snap.get("result") or {}

    if snap.get("kind") == "export_kb" or kind == "export":
        path = Path(result.get("excel_path") or "")
        dl_name = result.get("excel_name") or path.name or "kb_export.xlsx"
        if not path.is_file():
            return jsonify({"success": False, "error": "导出文件不存在或已清理"}), 404
        return send_file(path, as_attachment=True, download_name=dl_name)

    if snap.get("kind") != "qc":
        return jsonify({"success": False, "error": "该任务无可下载文件"}), 400

    if kind == "json":
        path = Path(result.get("json_path") or "")
        dl_name = result.get("json_name") or path.name
    elif kind == "csv":
        path = Path(result.get("csv_path") or "")
        dl_name = result.get("csv_name") or path.name
    else:
        path = Path(result.get("excel_path") or "")
        dl_name = result.get("excel_name") or path.name
    if not path.is_file():
        return jsonify({"success": False, "error": "结果文件不存在或已清理"}), 404
    return send_file(path, as_attachment=True, download_name=dl_name)


# ── 知识库导入/导出 ─────────────────────────────────────────


@qc_bp.route("/qc/kb_stats", methods=["GET"])
def rag_knowledge_qc_kb_stats():
    try:
        settings = load_kqc_settings()
        from backend.knowledge_qc.services.chroma_batch import create_chroma_store

        store = create_chroma_store(settings)
        qn = store.production.count()
        inn = store.intent_production.count()
        return jsonify(
            {
                "success": True,
                "question_count": qn,
                "intent_count": inn,
                "text": f"相似问 {qn} 条 | 意图描述 {inn} 条",
            }
        )
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/ingest_question", methods=["POST"])
def rag_knowledge_qc_ingest_question():
    try:
        if "file" not in request.files:
            return jsonify({"success": False, "error": "请上传 Excel 文件"}), 400
        f = request.files["file"]
        if not f or not f.filename:
            return jsonify({"success": False, "error": "未选择文件"}), 400
        clear = request.form.get("clear") in ("1", "true", "yes")
        input_path = _temp_uploaded_excel(f)
        output_dir = _temp_job_output_dir()

        job_id = _new_job("ingest_question")
        _set_job_temp_output_dir(job_id, output_dir)
        threading.Thread(
            target=_run_ingest_thread,
            args=(job_id, input_path, output_dir, "question", clear),
            daemon=True,
        ).start()
        return jsonify({"success": True, "job_id": job_id})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@qc_bp.route("/qc/ingest_intent", methods=["POST"])
def rag_knowledge_qc_ingest_intent():
    try:
        if "file" not in request.files:
            return jsonify({"success": False, "error": "请上传 Excel 文件"}), 400
        f = request.files["file"]
        if not f or not f.filename:
            return jsonify({"success": False, "error": "未选择文件"}), 400
        clear = request.form.get("clear") in ("1", "true", "yes")
        input_path = _temp_uploaded_excel(f)
        output_dir = _temp_job_output_dir()

        job_id = _new_job("ingest_intent")
        _set_job_temp_output_dir(job_id, output_dir)
        threading.Thread(
            target=_run_ingest_thread,
            args=(job_id, input_path, output_dir, "intent", clear),
            daemon=True,
        ).start()
        return jsonify({"success": True, "job_id": job_id})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def _run_ingest_thread(
    job_id: str,
    input_path: Path,
    output_dir: Path,
    kind: str,
    clear: bool,
) -> None:
    try:
        _run_ingest_thread_impl(job_id, input_path, output_dir, kind, clear)
    except Exception as e:  # noqa: BLE001
        _job_log(job_id, f"导入异常: {e}")
        _job_update(job_id, status="failed", error=str(e))
    finally:
        _remove_temp_upload(input_path)
        snap = _job_snapshot(job_id)
        if snap and snap.get("status") == "failed":
            _cleanup_job_temp_output(job_id)


def _run_ingest_thread_impl(
    job_id: str,
    input_path: Path,
    output_dir: Path,
    kind: str,
    clear: bool,
) -> None:
    from backend.knowledge_qc.loaders.excel_loader import load_intent_sheet, load_question_sheet
    from backend.knowledge_qc.models import QCTaskType
    from backend.knowledge_qc.services.chroma_batch import create_chroma_store
    from backend.knowledge_qc.services.embedder import create_embedder
    from backend.knowledge_qc.kb_ingest import ingest_intents, ingest_questions, progress_label

    settings = load_kqc_settings()
    rules = settings["rules"]
    id_cfg = rules.get("id", {})

    def on_progress(phase: str, current: int, total: int):
        _job_update(
            job_id,
            progress={
                "current": int(current),
                "total": int(total),
                "phase": phase,
                "label": progress_label(phase, current, total),
            },
        )

    def on_log(msg: str):
        _job_log(job_id, msg)

    on_log(f"正在初始化 {kind} 导入…")
    store = create_chroma_store(settings)
    embedder = create_embedder(settings)

    def _clear_collection(col, name: str):
        count = col.count()
        if count == 0:
            return
        on_log(f"正在清空 {name}（{count} 条）…")
        batch_size = 1000
        total_deleted = 0
        while total_deleted < count:
            data = col.get(limit=batch_size, include=[])
            ids = data.get("ids") or []
            if not ids:
                break
            col.delete(ids=ids)
            total_deleted += len(ids)
        on_log(f"{name} 清空完成")

    if clear:
        if kind == "intent":
            _clear_collection(store.intent_production, "意图生产库")
        else:
            _clear_collection(store.production, "相似问生产库")

    on_log("正在读取 Excel…")
    if kind == QCTaskType.INTENT.value:
        records = load_intent_sheet(input_path, rules, id_cfg)
        on_log(f"读取到 {len(records)} 条意图描述")
        count = ingest_intents(store, embedder, records, settings, on_progress=on_progress)
    else:
        records, _ = load_question_sheet(input_path, rules, id_cfg)
        on_log(f"读取到 {len(records)} 条相似问")
        count = ingest_questions(store, embedder, records, settings, on_progress=on_progress)

    on_log(f"导入完成，共 {count} 条")
    _job_update(
        job_id,
        status="completed",
        result={"count": count},
    )


@qc_bp.route("/qc/export_kb", methods=["POST"])
def rag_knowledge_qc_export_kb():
    try:
        output_dir = _temp_job_output_dir()
        job_id = _new_job("export_kb")
        _set_job_temp_output_dir(job_id, output_dir)
        threading.Thread(
            target=_run_export_thread,
            args=(job_id, output_dir),
            daemon=True,
        ).start()
        return jsonify({"success": True, "job_id": job_id})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


def _run_export_thread(job_id: str, output_dir: Path) -> None:
    try:
        _run_export_thread_impl(job_id, output_dir)
    except Exception as e:  # noqa: BLE001
        _job_log(job_id, f"导出异常: {e}")
        _job_update(job_id, status="failed", error=str(e))
    finally:
        snap = _job_snapshot(job_id)
        if snap and snap.get("status") == "failed":
            _cleanup_job_temp_output(job_id)


def _run_export_thread_impl(job_id: str, output_dir: Path) -> None:
    from backend.knowledge_qc.kb_export import export_production_to_excel

    _job_log(job_id, "正在读取向量库…")
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    excel_out = output_dir / f"kb_export_{ts}.xlsx"
    intent_count, question_count = export_production_to_excel(excel_out)
    _job_log(job_id, f"导出完成：相似问 {question_count} 条，意图描述 {intent_count} 条")
    _job_update(
        job_id,
        status="completed",
        result={
            "excel_path": str(excel_out),
            "excel_name": excel_out.name,
            "question_count": question_count,
            "intent_count": intent_count,
        },
    )
