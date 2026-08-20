from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Dict, List, TypeVar

from backend.knowledge_qc.loaders.excel_loader import (
    QcExcelResults,
    QuestionQcRow,
    intent_row_export,
    question_row_export,
    write_qc_excel,
)
from backend.knowledge_qc.models import (
    BatchReport,
    CheckResult,
    IntentBatchReport,
    IntentCheckResult,
    Verdict,
)
from backend.knowledge_qc.report.exporter import export_csv, export_json

T = TypeVar("T")
R = TypeVar("R")


def checkpoint_interval_from_rules(rules: dict) -> int:
    return max(0, int(rules.get("qc", {}).get("checkpoint_interval", 20)))


def build_batch_report(results: List[CheckResult]) -> BatchReport:
    passed = sum(1 for r in results if r.verdict == Verdict.PASS)
    failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
    errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
    return BatchReport(
        total=len(results),
        passed=passed,
        failed=failed,
        errors=errors,
        results=list(results),
        similarity_hits_by_record_id={},
    )


def build_intent_batch_report(results: List[IntentCheckResult]) -> IntentBatchReport:
    passed = sum(1 for r in results if r.verdict == Verdict.PASS)
    failed = sum(1 for r in results if r.verdict == Verdict.FAIL)
    errors = sum(1 for r in results if r.verdict == Verdict.ERROR)
    return IntentBatchReport(
        total=len(results),
        passed=passed,
        failed=failed,
        errors=errors,
        results=list(results),
    )


def question_results_map(
    results: List[CheckResult], rules: dict
) -> Dict[int, QuestionQcRow]:
    return {
        r.record.row_index: question_row_export(r, rules) for r in results
    }


def intent_results_map(
    results: List[IntentCheckResult], rules: dict
) -> Dict[int, QuestionQcRow]:
    return {
        r.record.row_index: intent_row_export(r, rules) for r in results
    }


def maybe_emit_checkpoint(
    results: List[T],
    done_count: int,
    interval: int,
    on_checkpoint: Callable[[R], None] | None,
    build_report: Callable[[List[T]], R],
) -> None:
    if not on_checkpoint or interval <= 0 or done_count % interval != 0:
        return
    on_checkpoint(build_report(results))


@dataclass
class QuestionCheckpointArgs:
    excel_out: Path
    json_path: Path
    csv_path: Path
    rules: dict
    report: BatchReport


def write_question_checkpoint(input_path: Path, args: QuestionCheckpointArgs) -> None:
    write_qc_excel(
        input_path,
        args.excel_out,
        args.rules,
        "question",
        QcExcelResults(
            question_results=question_results_map(args.report.results, args.rules),
        ),
    )
    export_json(args.report, args.json_path)
    export_csv(args.report, args.csv_path, rules=args.rules)


def write_intent_checkpoint(
    input_path: Path,
    excel_out: Path,
    rules: dict,
    report: IntentBatchReport,
) -> None:
    write_qc_excel(
        input_path,
        excel_out,
        rules,
        "intent",
        QcExcelResults(
            intent_results=intent_results_map(report.results, rules),
        ),
    )
