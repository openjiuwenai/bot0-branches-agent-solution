from __future__ import annotations

from typing import List, Optional, Tuple, TypeVar

T = TypeVar("T")


def row_range_bounds(rules: dict) -> Tuple[Optional[int], Optional[int]]:
    """解析 qc.row_start / row_end（Excel 行号，含端点；0 或空表示不限）。"""
    qc = rules.get("qc", {})
    start = _bound(qc.get("row_start"))
    end = _bound(qc.get("row_end"))
    if start is not None and end is not None and start > end:
        start, end = end, start
    return start, end


def _bound(raw) -> Optional[int]:
    if raw is None or raw == "":
        return None
    try:
        val = int(raw)
    except (TypeError, ValueError):
        return None
    return None if val <= 0 else val


def filter_by_row_range(records: List[T], rules: dict) -> List[T]:
    start, end = row_range_bounds(rules)
    if start is None and end is None:
        return list(records)
    out: List[T] = []
    for record in records:
        row_index = int(getattr(record, "row_index", 0))
        if start is not None and row_index < start:
            continue
        if end is not None and row_index > end:
            continue
        out.append(record)
    return out


def format_row_range_log(rules: dict, qc_count: int, total_count: int) -> str:
    start, end = row_range_bounds(rules)
    if start is None and end is None:
        return f"共 {total_count} 条待检"
    lo = start if start is not None else 1
    hi = end if end is not None else "末"
    return (
        f"共 {total_count} 条语料，质检行范围 第{lo}–{hi}行（{qc_count} 条）；"
        f"Embedding/staging 仍覆盖全表"
    )
