from __future__ import annotations

from typing import Any, Dict, List, Union

from backend.knowledge_qc.models import IntentRecord, QARecord

RecordLike = Union[QARecord, IntentRecord]


def filter_hits_for_record(
    hits: List[Dict[str, Any]], record: RecordLike
) -> List[Dict[str, Any]]:
    """检索结果中排除当前条自身（同 record_id 或本批同 Excel 行号）。"""
    filtered: List[Dict[str, Any]] = []
    for h in hits:
        if h.get("id") == record.record_id:
            continue
        if h.get("corpus_scope") == "batch":
            hit_row = h.get("row_index")
            if hit_row is not None and hit_row == record.row_index:
                continue
        filtered.append(h)
    return filtered
