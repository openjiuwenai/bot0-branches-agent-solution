from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Tuple

import pandas as pd

from backend.knowledge_qc.config import load_settings
from backend.knowledge_qc.services.chroma_batch import create_chroma_store

ID_COL = "知识id"
INTENT_NAME_COL = "意图名称"
INTENT_DESC_COL = "意图描述"
QUESTION_COL = "相似问"


def _rows_from_collection(
    col,
    *,
    question_mode: bool,
) -> List[Dict[str, Any]]:
    n = col.count()
    if n == 0:
        return []
    data = col.get(include=["documents", "metadatas"])
    ids = data.get("ids") or []
    docs = data.get("documents") or []
    metas = data.get("metadatas") or []
    rows: List[Dict[str, Any]] = []
    for i, doc_id in enumerate(ids):
        meta = metas[i] if i < len(metas) else {}
        meta = meta or {}
        intent_name = meta.get("intent_name") or meta.get("answer_menu", "")
        if question_mode:
            rows.append(
                {
                    ID_COL: doc_id,
                    INTENT_NAME_COL: intent_name,
                    QUESTION_COL: docs[i] if i < len(docs) else "",
                }
            )
        else:
            desc = meta.get("intent_description") or meta.get("description", "")
            if not desc and i < len(docs):
                desc = docs[i] if docs[i] else ""
            rows.append(
                {
                    ID_COL: doc_id,
                    INTENT_NAME_COL: intent_name,
                    INTENT_DESC_COL: desc,
                }
            )
    return rows


def export_production_to_excel(output_path: Path) -> Tuple[int, int]:
    """导出相似问生产库、意图生产库到一个 Excel（两个 sheet）。"""
    settings = load_settings()
    rules = settings["rules"]
    excel_cfg = rules.get("excel", {})
    intent_sheet = excel_cfg.get("intent_sheet", "意图信息")
    question_sheet = excel_cfg.get("question_sheet", "相似问信息")

    store = create_chroma_store(settings)
    q_rows = _rows_from_collection(store.production, question_mode=True)
    i_rows = _rows_from_collection(store.intent_production, question_mode=False)

    if not q_rows and not i_rows:
        raise ValueError("相似问库与意图库均为空，无可导出数据")

    output_path = Path(output_path)
    if output_path.suffix.lower() not in (".xlsx", ".xls"):
        output_path = output_path.with_suffix(".xlsx")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    intent_cols = [ID_COL, INTENT_NAME_COL, INTENT_DESC_COL]
    question_cols = [ID_COL, INTENT_NAME_COL, QUESTION_COL]

    with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
        pd.DataFrame(i_rows, columns=intent_cols).to_excel(
            writer, sheet_name=intent_sheet, index=False
        )
        pd.DataFrame(q_rows, columns=question_cols).to_excel(
            writer, sheet_name=question_sheet, index=False
        )

    return len(i_rows), len(q_rows)


def export_production_to_csv(output_path: Path) -> int:
    """兼容旧接口：仅导出相似问生产库为 CSV。"""
    settings = load_settings()
    store = create_chroma_store(settings)
    rows = _rows_from_collection(store.production, question_mode=True)
    if not rows:
        raise ValueError("相似问生产库为空，无可导出数据")
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(
        output_path, index=False, encoding="utf-8-sig"
    )
    return len(rows)
