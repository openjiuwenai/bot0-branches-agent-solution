#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Dict, List, Sequence

logger = logging.getLogger(__name__)


def norm_col_name(s: str) -> str:
    return str(s or "").replace(" ", "").replace("\u3000", "").strip().lower()


def cell_str(v) -> str:
    if v is None:
        return ""
    try:
        import pandas as pd

        if isinstance(v, float) and pd.isna(v):
            return ""
    except (ImportError, TypeError, ValueError):
        logger.debug("cell_str pandas na check failed", exc_info=True)
    s = str(v).strip()
    if s.lower() in ("nan", "none"):
        return ""
    return s


def load_badcases_from_excel(path: Path, sheet: str | int | None) -> List[Dict[str, str]]:
    import pandas as pd

    if str(path).lower().endswith(".csv"):
        df = pd.read_csv(path, encoding="utf-8-sig", dtype=str, engine="python")
    else:
        kw: dict = {"dtype": str}
        if sheet is not None:
            kw["sheet_name"] = sheet
        df = pd.read_excel(path, engine="openpyxl", **kw)

    if df is None or df.empty or df.shape[1] == 0:
        return []

    cols = [str(c) for c in df.columns.tolist()]
    ncol = [norm_col_name(c) for c in cols]

    def pick_q_idx() -> int:
        keys = (
            "用户原始query",
            "原始query",
            "customer_input",
            "question",
            "user_query",
            "std_query",
            "query",
            "input",
            "问题",
            "用户问题",
            "prompt",
        )
        for i, n in enumerate(ncol):
            for k in keys:
                if k in n:
                    return i
        return 0

    def pick_a_idx(q_idx: int) -> int:
        keys = ("答案", "answer", "intent", "output", "standard_answer", "菜单", "menu", "label", "target")
        for i, n in enumerate(ncol):
            if i == q_idx:
                continue
            for k in keys:
                if k in n:
                    return i
        if len(cols) > 1:
            return 1 if q_idx != 1 else 0
        return -1

    qi = pick_q_idx()
    ai = pick_a_idx(qi)
    qcol = cols[qi]
    acol = cols[ai] if ai >= 0 and ai < len(cols) else None

    out: List[Dict[str, str]] = []
    for idx, row in df.iterrows():
        try:
            q = cell_str(row[qcol]) if qcol in row.index else ""
        except Exception:
            q = ""
        try:
            a = cell_str(row[acol]) if acol and acol in row.index else ""
        except Exception:
            a = ""
        if not q.strip() or not a.strip():
            continue
        out.append({"id": str(idx), "query": q.strip(), "answer": a.strip()})
    return out


def write_frozen_qa_xlsx(path: Path, pairs: Sequence[Dict[str, Any]]) -> None:
    import pandas as pd

    rows = []
    for qa in pairs:
        if isinstance(qa, dict):
            row = {
                "q": str(qa.get("q") or qa.get("query") or "").strip(),
                "a": str(qa.get("a") or qa.get("answer") or "").strip(),
            }
            for k, v in qa.items():
                if k in {"q", "a", "query", "answer"}:
                    continue
                # 内部字段（如 cluster 复用 embedding）不应导出落盘
                if str(k).startswith("_"):
                    continue
                row[str(k)] = v
            rows.append(row)
    df_out = pd.DataFrame(rows)
    path.parent.mkdir(parents=True, exist_ok=True)
    df_out.to_excel(path, index=False, engine="openpyxl")

