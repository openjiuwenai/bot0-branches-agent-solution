from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List, Optional, Set

import pandas as pd

from backend.knowledge_qc.id_generator import generate_record_id
from backend.knowledge_qc.models import QARecord

QUESTION_ALIASES = ("用户问题", "相似问", "问题")
INTENT_NAME_ALIASES = ("意图名称", "答案", "菜单", "菜单名称")
INTENT_DESCRIPTION_ALIASES = ("意图描述", "答案描述", "菜单描述")


def resolve_csv_columns(columns, csv_cfg: Dict[str, Any]) -> Dict[str, str]:
    """将内部字段名映射到 CSV 实际列名。"""
    name_col = csv_cfg.get("intent_name_col") or csv_cfg.get("answer_col")
    desc_col = (
        csv_cfg.get("intent_description_col") or csv_cfg.get("description_col")
    )
    return {
        "question": _resolve_column(columns, csv_cfg.get("question_col"), QUESTION_ALIASES),
        "intent_name": _resolve_column(columns, name_col, INTENT_NAME_ALIASES),
        "intent_description": _resolve_column(
            columns, desc_col, INTENT_DESCRIPTION_ALIASES
        ),
    }


def _resolve_column(columns, primary: Optional[str], aliases: tuple) -> str:
    candidates = []
    if primary:
        candidates.append(primary)
    for name in aliases:
        if name not in candidates:
            candidates.append(name)
    for name in candidates:
        if name in columns:
            return name
    raise ValueError(f"CSV 缺少列，需要其一 {candidates}，当前列: {list(columns)}")


def load_qa_csv(path: Path, csv_cfg: Dict[str, Any], id_cfg: Dict[str, Any] = None) -> List[QARecord]:
    encoding = csv_cfg.get("encoding", "utf-8-sig")
    df = pd.read_csv(path, encoding=encoding)

    col_map = resolve_csv_columns(df.columns, csv_cfg)
    rename_map = {v: k for k, v in col_map.items()}
    id_col = (id_cfg or {}).get("column") or csv_cfg.get("id_col")
    if id_col and id_col in df.columns:
        rename_map[id_col] = "record_id"

    df = df.rename(columns=rename_map)

    for col in ("question", "intent_name", "intent_description"):
        if col not in df.columns:
            raise ValueError(
                f"CSV 缺少列映射: {col}，当前列: {list(df.columns)}"
            )

    strategy = (id_cfg or {}).get("strategy", "uuid")
    seen_ids: Set[str] = set()
    records: List[QARecord] = []

    for idx, row in df.iterrows():
        q = _cell_str(row.get("question"))
        intent_name = _cell_str(row.get("intent_name"))
        intent_desc = _cell_str(row.get("intent_description"))
        row_index = int(idx) + 1
        explicit = _cell_str(row.get("record_id")) if "record_id" in df.columns else ""

        record_id = generate_record_id(
            q, intent_name, row_index, strategy=strategy, explicit_id=explicit or None
        )
        if record_id in seen_ids:
            raise ValueError(f"CSV 内 record_id 重复: {record_id}（约第 {row_index} 行）")
        seen_ids.add(record_id)

        records.append(
            QARecord(
                record_id=record_id,
                question=q,
                intent_name=intent_name,
                intent_description=intent_desc,
                row_index=row_index,
            )
        )
    return records


def _cell_str(val) -> str:
    if val is None or (isinstance(val, float) and pd.isna(val)):
        return ""
    return str(val).strip()
