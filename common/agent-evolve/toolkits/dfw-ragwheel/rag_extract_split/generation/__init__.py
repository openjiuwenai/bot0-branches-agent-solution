#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""QA generation strategies (LLM / Cluster / Fallback)."""

from rag_extract_split.generation.orchestrator import (
    generate_qa_pairs_for_answer_category,
    append_llm_trace,
    append_cluster_round_trace,
    llm_trace_enabled,
)
from rag_extract_split.generation.llm import generate_qa_pairs_llm_one_answer
from rag_extract_split.generation.cluster import (
    generate_qa_pairs_cluster_one_answer,
    generate_qa_pairs_fallback,
    empty_cluster_meta,
)

__all__ = [
    "generate_qa_pairs_for_answer_category",
    "append_llm_trace",
    "append_cluster_round_trace",
    "llm_trace_enabled",
    "generate_qa_pairs_llm_one_answer",
    "generate_qa_pairs_cluster_one_answer",
    "generate_qa_pairs_fallback",
    "empty_cluster_meta",
]
