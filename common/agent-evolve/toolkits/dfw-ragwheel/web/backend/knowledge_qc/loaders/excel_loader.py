from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, NamedTuple, Optional, Tuple

import pandas as pd

from backend.knowledge_qc.checkers.corpus_scope import CORPUS_BATCH, CORPUS_PRODUCTION
from backend.knowledge_qc.id_generator import generate_record_id
from backend.knowledge_qc.models import (
    CheckResult,
    IntentCheckResult,
    IntentRecord,
    QARecord,
    Verdict,
)

QC_VERDICT_COL = "质检结论"
QC_REASON_COL = "不通过原因"
QC_REASON_BATCH_COL = "不通过原因-本批语料"
QC_REASON_PROD_COL = "不通过原因-生产库"
QC_DIMENSION_COLUMNS = (
    "格式规范",
    "合规安全",
    "重复检测",
    "冲突检测",
    "语义质量",
)
DIMENSION_REASON_COLUMNS = {
    "格式规范": "格式规范不通过原因",
    "合规安全": "合规安全不通过原因",
    "重复检测": "重复检测不通过原因",
    "冲突检测": "冲突检测不通过原因",
    "语义质量": "语义质量不通过原因",
}
CHECKER_KEY_BY_DIMENSION = {
    "格式规范": "format",
    "合规安全": "compliance",
    "重复检测": "duplicate",
    "冲突检测": "conflict",
    "语义质量": "semantic",
}
DIMENSION_STATUS_PASS = "通过"
DIMENSION_STATUS_FAIL = "不通过"
DIMENSION_STATUS_SKIPPED = "未执行"


class QuestionQcRow(NamedTuple):
    verdict: str
    reason_text: str
    dim_statuses: Dict[str, str]
    dim_reason_texts: Dict[str, str]
    batch_reasons: List[str]
    prod_reasons: List[str]


@dataclass
class QcExcelResults:
    question_results: Optional[Dict[int, QuestionQcRow]] = None
    intent_results: Optional[Dict[int, QuestionQcRow]] = None


def is_checker_enabled(rules: dict, dimension_label: str) -> bool:
    key = CHECKER_KEY_BY_DIMENSION.get(dimension_label)
    if not key:
        return False
    return bool(rules.get("checkers", {}).get(key, True))


def mark_dimensions_skipped(
    dim_statuses: Dict[str, str],
    checkers_after,
    rules: dict,
) -> None:
    for checker in checkers_after:
        dim = checker.dimension
        if is_checker_enabled(rules, dim):
            dim_statuses[dim] = DIMENSION_STATUS_SKIPPED


def question_dimension_statuses(
    result: CheckResult, rules: dict
) -> Dict[str, str]:
    """各检测项：通过 / 不通过 / 未执行；未启用的检测项留空。"""
    stored = result.dimension_statuses or {}
    out: Dict[str, str] = {}
    for label in QC_DIMENSION_COLUMNS:
        if not is_checker_enabled(rules, label):
            out[label] = ""
        else:
            out[label] = stored.get(label, DIMENSION_STATUS_PASS)
    return out


def init_intent_dimension_statuses(rules: dict) -> Dict[str, str]:
    """意图质检分项初始状态：未勾选或未执行的项为「未执行」。"""
    return {label: DIMENSION_STATUS_SKIPPED for label in QC_DIMENSION_COLUMNS}


def intent_dimension_statuses(
    result: IntentCheckResult, rules: dict
) -> Dict[str, str]:
    """意图质检分项：未勾选为未执行；已勾选取检测结果。"""
    stored = result.dimension_statuses or {}
    out: Dict[str, str] = {}
    for label in QC_DIMENSION_COLUMNS:
        if not is_checker_enabled(rules, label):
            out[label] = DIMENSION_STATUS_SKIPPED
        else:
            out[label] = stored.get(label, DIMENSION_STATUS_SKIPPED)
    return out


def _excel_cfg(rules: dict) -> dict:
    return rules.get("excel", {})


def _read_sheet(path: Path, sheet_name: str) -> pd.DataFrame:
    df = pd.read_excel(path, sheet_name=sheet_name, engine="openpyxl")
    df.columns = [str(c).strip() for c in df.columns]
    return df


def load_intent_sheet(path: Path, rules: dict, id_cfg: dict) -> List[IntentRecord]:
    cfg = _excel_cfg(rules)
    sheet = cfg.get("intent_sheet", "意图信息")
    name_col = cfg.get("intent_name_col", "意图名称")
    desc_col = cfg.get("intent_description_col", "意图描述")
    df = _read_sheet(path, sheet)
    for col in (name_col, desc_col):
        if col not in df.columns:
            raise ValueError(
                f"Sheet「{sheet}」缺少列「{col}」，当前列: {list(df.columns)}"
            )

    strategy = (id_cfg or {}).get("strategy", "uuid")
    records: List[IntentRecord] = []
    seen: set = set()
    for idx, row in df.iterrows():
        name = _cell(row.get(name_col))
        desc = _cell(row.get(desc_col))
        row_index = int(idx) + 1
        rid = generate_record_id(name, desc, row_index, strategy=strategy)
        if rid in seen:
            raise ValueError(f"意图 record_id 重复: {rid}（约第 {row_index} 行）")
        seen.add(rid)
        records.append(
            IntentRecord(
                record_id=rid,
                intent_name=name,
                intent_description=desc,
                row_index=row_index,
            )
        )
    return records


def load_question_sheet(
    path: Path, rules: dict, id_cfg: dict
) -> Tuple[List[QARecord], Dict[str, str]]:
    """加载相似问 sheet，并按意图名称合并意图描述。"""
    cfg = _excel_cfg(rules)
    q_sheet = cfg.get("question_sheet", "相似问信息")
    i_sheet = cfg.get("intent_sheet", "意图信息")
    name_col = cfg.get("intent_name_col", "意图名称")
    desc_col = cfg.get("intent_description_col", "意图描述")
    q_col = cfg.get("question_col", "相似问")

    intent_df = _read_sheet(path, i_sheet)
    desc_by_name: Dict[str, str] = {}
    for _, row in intent_df.iterrows():
        n = _cell(row.get(name_col))
        if n:
            desc_by_name[n] = _cell(row.get(desc_col))

    df = _read_sheet(path, q_sheet)
    for col in (name_col, q_col):
        if col not in df.columns:
            raise ValueError(
                f"Sheet「{q_sheet}」缺少列「{col}」，当前列: {list(df.columns)}"
            )

    strategy = (id_cfg or {}).get("strategy", "uuid")
    records: List[QARecord] = []
    seen: set = set()
    for idx, row in df.iterrows():
        question = _cell(row.get(q_col))
        intent_name = _cell(row.get(name_col))
        intent_desc = desc_by_name.get(intent_name, "")
        row_index = int(idx) + 1
        rid = generate_record_id(
            question, intent_name, row_index, strategy=strategy
        )
        if rid in seen:
            raise ValueError(f"相似问 record_id 重复: {rid}（约第 {row_index} 行）")
        seen.add(rid)
        records.append(
            QARecord(
                record_id=rid,
                question=question,
                intent_name=intent_name,
                intent_description=intent_desc,
                row_index=row_index,
            )
        )
    return records, desc_by_name


def question_row_export(result: CheckResult, rules: dict) -> QuestionQcRow:
    batch_reasons = []
    prod_reasons = []
    dim_reasons: Dict[str, List[str]] = {}
    for issue in result.issues:
        if issue.passed:
            continue
        dim = issue.dimension
        if dim in QC_DIMENSION_COLUMNS:
            if dim not in dim_reasons:
                dim_reasons[dim] = []
            dim_reasons[dim].append(issue.reason)
        scope = issue.corpus_scope or ""
        if scope == CORPUS_BATCH:
            batch_reasons.append(issue.reason)
        elif scope == CORPUS_PRODUCTION:
            prod_reasons.append(issue.reason)
        else:
            # 非重复/冲突项（格式、合规、语义）的原因同时放入两列
            batch_reasons.append(issue.reason)
            prod_reasons.append(issue.reason)

    # 重新聚合总体不通过原因
    all_reasons = []
    for issue in result.issues:
        if not issue.passed:
            all_reasons.append(issue.reason)
    verdict, reason_text = verdict_to_row(result.verdict, "；".join(all_reasons))

    dim_reason_texts = {
        label: "；".join(dim_reasons.get(label, [])) for label in QC_DIMENSION_COLUMNS
    }
    return QuestionQcRow(
        verdict=verdict,
        reason_text=reason_text,
        dim_statuses=question_dimension_statuses(result, rules),
        dim_reason_texts=dim_reason_texts,
        batch_reasons=batch_reasons,
        prod_reasons=prod_reasons,
    )


def intent_row_export(result: IntentCheckResult, rules: dict) -> QuestionQcRow:
    all_reasons = []
    if result.reason:
        all_reasons.append(result.reason)
    verdict, reason_text = verdict_to_row(result.verdict, "；".join(all_reasons))
    dim_statuses = intent_dimension_statuses(result, rules)
    fail_reason = ""
    if dim_statuses.get("重复检测") == DIMENSION_STATUS_FAIL:
        fail_reason = result.reason
    dim_reason_texts = {"重复检测": fail_reason}
    return QuestionQcRow(
        verdict=verdict,
        reason_text=reason_text,
        dim_statuses=dim_statuses,
        dim_reason_texts=dim_reason_texts,
        batch_reasons=[],
        prod_reasons=[],
    )


def write_qc_excel(
    source_path: Path,
    output_path: Path,
    rules: dict,
    task: str,
    results: Optional[QcExcelResults] = None,
) -> None:
    """在对应 sheet 末尾追加质检结论、分项结论、分项原因与不通过原因列并写出 Excel。"""
    payload = results or QcExcelResults()
    question_results = payload.question_results
    intent_results = payload.intent_results
    xl = pd.ExcelFile(source_path, engine="openpyxl")
    sheets: Dict[str, pd.DataFrame] = {
        name: pd.read_excel(source_path, sheet_name=name, engine="openpyxl")
        for name in xl.sheet_names
    }

    mode = rules.get("detection_mode", {})
    batch_on = bool(mode.get("batch", True))
    prod_on = bool(mode.get("production", True))

    def _drop_cols() -> List[str]:
        cols = [
            QC_VERDICT_COL,
            QC_REASON_COL,
            QC_REASON_BATCH_COL,
            QC_REASON_PROD_COL,
            *QC_DIMENSION_COLUMNS,
            *DIMENSION_REASON_COLUMNS.values(),
        ]
        return cols

    def _apply_question(df: pd.DataFrame, results: Dict[int, QuestionQcRow]) -> pd.DataFrame:
        out = df.copy()
        out.columns = [str(c).strip() for c in out.columns]
        drop_cols = _drop_cols()
        out = out.drop(columns=[c for c in drop_cols if c in out.columns], errors="ignore")
        verdicts: List[str] = []
        reasons: List[str] = []
        batch_reasons: List[str] = []
        prod_reasons: List[str] = []
        dim_values: Dict[str, List[str]] = {label: [] for label in QC_DIMENSION_COLUMNS}
        dim_reason_values: Dict[str, List[str]] = {label: [] for label in QC_DIMENSION_COLUMNS}
        for i in range(len(out)):
            row_index = i + 1
            if row_index in results:
                row = results[row_index]
                verdicts.append(row.verdict)
                reasons.append(row.reason_text)
                batch_reasons.append("；".join(row.batch_reasons))
                prod_reasons.append("；".join(row.prod_reasons))
                for label in QC_DIMENSION_COLUMNS:
                    dim_values[label].append(row.dim_statuses.get(label, ""))
                    dim_reason_values[label].append(row.dim_reason_texts.get(label, ""))
            else:
                verdicts.append("")
                reasons.append("")
                batch_reasons.append("")
                prod_reasons.append("")
                for label in QC_DIMENSION_COLUMNS:
                    dim_values[label].append("")
                    dim_reason_values[label].append("")
        out[QC_VERDICT_COL] = verdicts
        for label in QC_DIMENSION_COLUMNS:
            out[label] = dim_values[label]
            if task == "question" or label == "重复检测":
                out[DIMENSION_REASON_COLUMNS[label]] = dim_reason_values[label]
        if task == "question":
            if batch_on:
                out[QC_REASON_BATCH_COL] = batch_reasons
            if prod_on:
                out[QC_REASON_PROD_COL] = prod_reasons
        out[QC_REASON_COL] = reasons
        return out

    cfg = _excel_cfg(rules)
    if task == "question" and question_results is not None:
        sheet = cfg.get("question_sheet", "相似问信息")
        if sheet in sheets:
            sheets[sheet] = _apply_question(sheets[sheet], question_results)
    if task == "intent" and intent_results is not None:
        sheet = cfg.get("intent_sheet", "意图信息")
        if sheet in sheets:
            sheets[sheet] = _apply_question(sheets[sheet], intent_results)

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
        for name, df in sheets.items():
            df.to_excel(writer, sheet_name=name, index=False)


def verdict_to_row(result_verdict: Verdict, issues_reason: str) -> Tuple[str, str]:
    if result_verdict == Verdict.PASS:
        return Verdict.PASS.value, ""
    return result_verdict.value, issues_reason


def _cell(val) -> str:
    if val is None or (isinstance(val, float) and pd.isna(val)):
        return ""
    return str(val).strip()
