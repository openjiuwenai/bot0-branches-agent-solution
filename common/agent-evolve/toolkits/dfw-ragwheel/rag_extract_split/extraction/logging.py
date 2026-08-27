#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import logging
from typing import Any, Dict, Sequence

logger = logging.getLogger(__name__)


def build_round_log_entry(
    *,
    round_num: int,
    run_tag: str,
    target_recall: float,
    alloc_mode: str,
    budget: int,
    count: int,
    completion_mode: str,
    gen_total: int,
    round_chroma_ids_len: int,
    llm_cat: int,
    fb_cat: int,
    cluster_cat: int,
    recall_rate: float,
    all_ok: bool,
    retained_qa_this_round: int,
    frozen_total: int,
    frozen_list: Sequence[str],
    newly_done_list: Sequence[str],
    emb_after: Dict[str, int],
) -> Dict[str, Any]:
    return {
        "round": round_num,
        "run_tag": run_tag,
        "target_recall": target_recall,
        "alloc_mode": alloc_mode,
        "budget": budget,
        "current_count_param": count,
        "completion_mode": completion_mode,
        "gen_total": gen_total,
        "temp_upsert_ids": round_chroma_ids_len,
        "llm_categories": llm_cat,
        "fallback_categories": fb_cat,
        "cluster_categories": cluster_cat,
        "llm_called": llm_cat > 0,
        "recall_rate": recall_rate,
        "all_ok": all_ok,
        "retained_qa_this_round": retained_qa_this_round,
        "frozen_answer_categories_total": frozen_total,
        "frozen_answer_categories": list(frozen_list),
        "newly_frozen_answer_categories": list(newly_done_list),
        "embedding": {
            "local_batches": emb_after["local_batches"],
            "local_texts": emb_after["local_texts"],
            "remote_batches": emb_after["remote_batches"],
            "remote_texts": emb_after["remote_texts"],
            "hashing_full_fallback": emb_after["hashing_full_fallback"],
            "hashing_vectors": emb_after["hashing_vectors"],
            "embedding_local_called": emb_after["local_batches"] > 0,
            "embedding_remote_called": emb_after["remote_batches"] > 0,
            "embedding_hashing_used": emb_after["hashing_full_fallback"] > 0 or emb_after["hashing_vectors"] > 0,
        },
    }


def print_round_verbose(
    *,
    round_num: int,
    completion_mode: str,
    alloc_mode: str,
    budget: int,
    count: int,
    target_recall: float,
    active: Sequence[str],
    alloc: Dict[str, int],
    gen_total: int,
    round_chroma_ids_len: int,
    llm_cat: int,
    fb_cat: int,
    cluster_cat: int,
    recall_rate: float,
    all_ok: bool,
    retained_qa_this_round: int,
    frozen_total: int,
    newly_done_list: Sequence[str],
    frozen_list: Sequence[str],
    emb_after: Dict[str, int],
) -> None:
    alloc_line = " | ".join(f"{a}={int(alloc.get(a, 0) or 0)}" for a in active) if active else "-"
    logger.info(
        "%s",
        (
            f"\n=== 第 {round_num} 轮 ===\n"
            f"  模式: {completion_mode}\n"
            f"  分配策略: {alloc_mode} | budget={budget} | "
            f"count参数={count} | 目标召回={target_recall}\n"
            f"  本轮每类额度: {alloc_line}\n"
            f"  生成 QA 条数: {gen_total} | 临时入库向量数: {round_chroma_ids_len}\n"
            f"  生成来源: LLM类目={llm_cat} | 模板回退类目={fb_cat} | "
            f"聚类类目={cluster_cat} | 是否调用LLM={'是' if llm_cat > 0 else '否'}\n"
            f"  召回: rate={recall_rate:.6f} | all_ok={all_ok} | "
            f"本轮新增保留 QA={retained_qa_this_round} | "
            f"已冻结答案类别总数={frozen_total}\n"
            f"  冻结答案类别: 本轮新冻结={list(newly_done_list)} | "
            f"已冻结全量={list(frozen_list)}\n"
            f"  Embedding: 本地批次={emb_after['local_batches']} "
            f"本地文本数={emb_after['local_texts']} | "
            f"远程批次={emb_after['remote_batches']} "
            f"远程文本数={emb_after['remote_texts']} | "
            f"hashing回退次数={emb_after['hashing_full_fallback']} "
            f"hashing向量数={emb_after['hashing_vectors']} | "
            f"是否走本地embedding={'是' if emb_after['local_batches'] > 0 else '否'}"
        ),
    )
