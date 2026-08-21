#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List

from rag_extract_split.common.helpers import now_ms


@dataclass(frozen=True)
class RAGCase:
    case_id: str
    query: str
    answer: str
    metadata: Dict[str, Any]


@dataclass
class ExtractIteration:
    round_num: int
    qa_count: int
    recall_rate: float
    ts_ms: int = field(default_factory=now_ms)


@dataclass
class ExtractResult:
    task_id: str
    status: str
    collection: str
    target_kb: str
    last_error: str = ""
    iterations: List[ExtractIteration] = field(default_factory=list)
    final_qa_pairs: List[Dict[str, Any]] = field(default_factory=list)  # {q,a}
    round_logs: List[Dict[str, Any]] = field(default_factory=list)

