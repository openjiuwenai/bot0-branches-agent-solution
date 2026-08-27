#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Core extraction pipeline."""

from rag_extract_split.extraction.engine import run_extract
from rag_extract_split.extraction.allocator import (
    ordered_answer_keys,
    allocate_round_category_counts,
    proportional_integers,
    recall_based_weights,
)
from rag_extract_split.extraction.round import (
    generate_and_upsert_round,
    prepare_round_allocation,
    append_cluster_trace_if_needed,
)
from rag_extract_split.extraction.logging import (
    build_round_log_entry,
    print_round_verbose,
)
from rag_extract_split.extraction.evaluator import evaluate_recall_detail
from rag_extract_split.extraction.postprocess import attach_high_similarity_hits

__all__ = [
    "run_extract",
    "ordered_answer_keys",
    "allocate_round_category_counts",
    "proportional_integers",
    "recall_based_weights",
    "generate_and_upsert_round",
    "prepare_round_allocation",
    "append_cluster_trace_if_needed",
    "build_round_log_entry",
    "print_round_verbose",
    "evaluate_recall_detail",
    "attach_high_similarity_hits",
]
