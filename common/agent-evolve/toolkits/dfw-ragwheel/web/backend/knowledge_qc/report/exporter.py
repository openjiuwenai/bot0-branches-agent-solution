from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

import pandas as pd

from backend.knowledge_qc.checkers.corpus_scope import format_row_ref
from backend.knowledge_qc.loaders.excel_loader import (
    QC_DIMENSION_COLUMNS,
    question_dimension_statuses,
)
from backend.knowledge_qc.models import (
    BatchReport,
    CheckResult,
    IntentBatchReport,
    IntentCheckResult,
    IntentRecord,
    Issue,
    QARecord,
    Verdict,
)

logger = logging.getLogger(__name__)


def timestamped_report_paths(output_dir: Union[str, Path]) -> Tuple[Path, Path]:
    """生成带时间戳的报告路径，避免多次运行覆盖。"""
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    out = Path(output_dir)
    return out / f"report_{ts}.json", out / f"report_{ts}.csv"


def _emit_lines(lines: List[str]) -> str:
    for line in lines:
        logger.info("%s", line)
    return "\n".join(lines) + "\n"


def print_intent_summary(report: IntentBatchReport) -> str:
    err = getattr(report, "errors", 0)
    line = f"  通过: {report.passed}  不通过: {report.failed}"
    if err:
        line += f"  异常: {err}"
    lines = [
        "=" * 60,
        f"意图描述质检完成：共 {report.total} 条",
        line,
        "=" * 60,
    ]
    for r in report.results:
        lines.append(
            f"\n[{format_row_ref(r.record.row_index)}] {r.verdict.value} | "
            f"{r.record.intent_name}"
        )
        if r.reason:
            lines.append(f"  原因: {r.reason}")
    return _emit_lines(lines)


def print_summary(report: BatchReport) -> str:
    err = getattr(report, "errors", 0)
    summary = f"  通过: {report.passed}  不通过: {report.failed}"
    if err:
        summary += f"  异常: {err}"
    lines = [
        "=" * 60,
        f"质检完成：共 {report.total} 条",
        summary,
        "=" * 60,
    ]
    for r in report.results:
        status = r.verdict.value
        lines.append(
            f"\n[{format_row_ref(r.record.row_index)}] {status} | {r.record.question}"
        )
        lines.append(f"  意图名称: {r.record.intent_name}")
        if r.issues:
            for issue in r.issues:
                if issue.passed:
                    continue
                lines.append(f"  - [{issue.dimension}] {issue.reason}")
                lines.append(f"    建议: {issue.suggestion}")
                if issue.fixed_question:
                    lines.append(f"    修复后相似问: {issue.fixed_question}")
                if issue.fixed_intent_description:
                    lines.append(f"    修复后意图描述: {issue.fixed_intent_description}")
        else:
            lines.append(f"  → {r.final_action}")
    return _emit_lines(lines)


def export_intent_json(report: IntentBatchReport, path: Union[str, Path]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "summary": {
            "total": report.total,
            "passed": report.passed,
            "failed": report.failed,
            "errors": getattr(report, "errors", 0),
        },
        "results": [_intent_result_to_dict(r) for r in report.results],
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def _intent_result_to_dict(r: IntentCheckResult) -> dict:
    return {
        "row_index": r.record.row_index,
        "display_ref": format_row_ref(r.record.row_index),
        "record_id": r.record.record_id,
        "intent_name": r.record.intent_name,
        "intent_description": r.record.intent_description,
        "verdict": r.verdict.value,
        "reason": r.reason,
        "dimension_statuses": dict(r.dimension_statuses or {}),
    }


def _verdict_from_value(value: str) -> Verdict:
    text = str(value or "").strip()
    for item in Verdict:
        if text in {item.value, item.name}:
            return item
    if text in {"质检过程异常"}:
        return Verdict.ERROR
    return Verdict.FAIL


def _check_result_from_dict(entry: dict) -> CheckResult:
    record = QARecord(
        record_id=str(entry.get("record_id") or ""),
        question=str(entry.get("question") or ""),
        intent_name=str(entry.get("意图名称") or entry.get("intent_name") or ""),
        intent_description=str(
            entry.get("意图描述") or entry.get("intent_description") or ""
        ),
        row_index=int(entry.get("row_index") or 0),
    )
    issues = [
        Issue(
            dimension=str(i.get("dimension") or ""),
            rule_id=str(i.get("rule_id") or ""),
            severity="high",
            reason=str(i.get("reason") or ""),
            suggestion="",
        )
        for i in (entry.get("issues") or [])
    ]
    return CheckResult(
        record=record,
        verdict=_verdict_from_value(str(entry.get("verdict") or "")),
        issues=issues,
        dimension_statuses=dict(entry.get("dimension_statuses") or {}),
    )


def _intent_result_from_dict(entry: dict) -> IntentCheckResult:
    record = IntentRecord(
        record_id=str(entry.get("record_id") or ""),
        intent_name=str(entry.get("intent_name") or ""),
        intent_description=str(entry.get("intent_description") or ""),
        row_index=int(entry.get("row_index") or 0),
    )
    return IntentCheckResult(
        record=record,
        verdict=_verdict_from_value(str(entry.get("verdict") or "")),
        reason=str(entry.get("reason") or ""),
        dimension_statuses=dict(entry.get("dimension_statuses") or {}),
    )


def _batch_report_from_entries(
    entries: List[dict],
    *,
    similarity_hits_by_record_id: Optional[Dict[str, List[Dict[str, Any]]]] = None,
) -> BatchReport:
    results = [_check_result_from_dict(e) for e in entries]
    passed = sum(1 for r in results if r.verdict == Verdict.PASS)
    failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
    errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
    return BatchReport(
        total=len(results),
        passed=passed,
        failed=failed,
        errors=errors,
        results=results,
        similarity_hits_by_record_id=dict(similarity_hits_by_record_id or {}),
    )


def _intent_batch_report_from_entries(entries: List[dict]) -> IntentBatchReport:
    results = [_intent_result_from_dict(e) for e in entries]
    passed = sum(1 for r in results if r.verdict == Verdict.PASS)
    failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
    errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
    return IntentBatchReport(
        total=len(results),
        passed=passed,
        failed=failed,
        errors=errors,
        results=results,
    )


def merge_question_reports(
    prior_report: Dict[str, Any],
    retry_report: BatchReport,
) -> BatchReport:
    """将续检结果按 Excel 行号合并进完整报告。"""
    by_row: Dict[int, dict] = {
        int(item["row_index"]): dict(item)
        for item in (prior_report.get("results") or [])
        if item.get("row_index") is not None
    }
    for result in retry_report.results:
        by_row[result.record.row_index] = _result_to_dict(result)
    merged_entries = [by_row[row] for row in sorted(by_row)]
    prior_hits = dict(prior_report.get("similarity_hits_by_record_id") or {})
    if not prior_hits and retry_report.similarity_hits_by_record_id:
        prior_hits = dict(retry_report.similarity_hits_by_record_id)
    return _batch_report_from_entries(
        merged_entries,
        similarity_hits_by_record_id=prior_hits,
    )


def merge_intent_reports(
    prior_report: Dict[str, Any],
    retry_report: IntentBatchReport,
) -> IntentBatchReport:
    by_row: Dict[int, dict] = {
        int(item["row_index"]): dict(item)
        for item in (prior_report.get("results") or [])
        if item.get("row_index") is not None
    }
    for result in retry_report.results:
        by_row[result.record.row_index] = _intent_result_to_dict(result)
    merged_entries = [by_row[row] for row in sorted(by_row)]
    return _intent_batch_report_from_entries(merged_entries)


def validate_retry_coverage(
    expected_rows: set[int],
    results,
    *,
    row_getter,
) -> None:
    got = {int(row_getter(r)) for r in results}
    missing = set(expected_rows) - got
    if missing:
        rows = ", ".join(str(x) for x in sorted(missing))
        raise ValueError(f"部分异常行未能重检（行号 {rows}），请重新完整质检")


def export_json(report: BatchReport, path: Union[str, Path]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "summary": {
            "total": report.total,
            "passed": report.passed,
            "failed": report.failed,
            "errors": getattr(report, "errors", 0),
        },
        "similarity_hits_by_record_id": dict(
            getattr(report, "similarity_hits_by_record_id", None) or {}
        ),
        "results": [_result_to_dict(r) for r in report.results],
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)


def export_csv(
    report: BatchReport, path: Union[str, Path], rules: dict = None
) -> None:
    rules = rules or {}
    rows = []
    for r in report.results:
        failed_issues = [i for i in r.issues if not i.passed]
        row = {
            "行号": r.record.row_index,
            "用户问题": r.record.question,
            "意图名称": r.record.intent_name,
            "意图描述": r.record.intent_description,
            "质检结论": r.verdict.value,
            "不通过原因": "；".join(i.reason for i in failed_issues),
        }
        if rules:
            row.update(question_dimension_statuses(r, rules))
        else:
            for label in QC_DIMENSION_COLUMNS:
                row[label] = ""
        rows.append(row)
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(path, index=False, encoding="utf-8-sig")


def _result_to_dict(r: CheckResult) -> dict:
    return {
        "row_index": r.record.row_index,
        "display_ref": format_row_ref(r.record.row_index),
        "record_id": r.record.record_id,
        "question": r.record.question,
        "意图名称": r.record.intent_name,
        "意图描述": r.record.intent_description,
        "verdict": r.verdict.value,
        "dimension_statuses": dict(r.dimension_statuses or {}),
        "issues": [
            {
                "dimension": i.dimension,
                "rule_id": i.rule_id,
                "reason": i.reason,
            }
            for i in r.issues
            if not i.passed
        ],
    }


def load_question_report_json(path: Union[str, Path]) -> Dict[str, Any]:
    """加载相似问质检报告 JSON。"""
    path = Path(path)
    if not path.is_file():
        return {}
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def load_intent_report_json(path: Union[str, Path]) -> Dict[str, Any]:
    """加载意图描述质检报告 JSON。"""
    return load_question_report_json(path)


def error_row_indices_question(report: Dict[str, Any]) -> List[int]:
    """返回 verdict 为质检异常的行号列表。"""
    results = report.get("results") or []
    out: List[int] = []
    for entry in results:
        if entry.get("row_index") is None:
            continue
        if _verdict_from_value(str(entry.get("verdict") or "")) == Verdict.ERROR:
            out.append(int(entry["row_index"]))
    return out


def error_row_indices_intent(report: Dict[str, Any]) -> List[int]:
    """返回意图描述质检中 verdict 为 ERROR 的行号列表。"""
    return error_row_indices_question(report)


def count_retryable_error_rows(prior_result: Dict[str, Any]) -> int:
    """根据 JSON 报告统计当前仍可续检的异常行数。"""
    task = str(prior_result.get("task") or "question").strip()
    report_json = Path(str(prior_result.get("json_path") or ""))
    if not report_json.is_file():
        return 0
    if task == "intent":
        report = load_intent_report_json(report_json)
    else:
        report = load_question_report_json(report_json)
    return len(error_row_indices_question(report))
