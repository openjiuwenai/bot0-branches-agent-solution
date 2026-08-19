#!/usr/bin/env python3
# -*- coding: utf-8 -*-
from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Set

from rag_extract_split.extraction.allocator import ordered_answer_keys
from rag_extract_split.infrastructure.chroma_store import clear_collection_fully, delete_ids
from rag_extract_split.io.data_io import write_frozen_qa_xlsx
from rag_extract_split.infrastructure.embedding import embed_stats_snapshot, embed_texts, reset_embed_stats
from rag_extract_split.extraction.evaluator import evaluate_recall_detail
from rag_extract_split.extraction.logging import build_round_log_entry, print_round_verbose
from rag_extract_split.extraction.postprocess import attach_high_similarity_hits
from rag_extract_split.extraction.round import append_cluster_trace_if_needed, generate_and_upsert_round, prepare_round_allocation
from rag_extract_split.config.models import ExtractIteration, ExtractResult
from rag_extract_split.config.settings import CONFIG


def safe_progress(
    callback: Optional[Callable[[Dict[str, Any]], None]],
    payload: Dict[str, Any],
) -> None:
    if callback is None:
        return
    try:
        callback(payload)
    except Exception:
        pass


def collection_name_from_target(target_kb: str) -> str:
    # 按传入名称原样使用，不做前缀拼接和字符替换。
    return str(target_kb if target_kb is not None else "default")


def _delete_round_vectors(collection: str, ids: Sequence[str]) -> int:
    try:
        bs = int(CONFIG.get("chroma", {}).get("delete_batch_size") or 1000)
        return delete_ids(collection, ids, batch_size=bs)
    except Exception:
        return 0


def _advance_generation_strategy(
    *,
    count: int,
    top_k: int,
    ordered_answers: Sequence[str],
    frozen_answers_done: Set[str],
) -> int:
    pending = len([a for a in ordered_answers if a not in frozen_answers_done])
    if pending <= 0:
        return count + 1
    _ = top_k  # 保留参数兼容旧调用：count 增长不再受 top_k 上限影响
    return count + int(pending)


def run_extract(
    *,
    badcases: Sequence[Dict[str, Any]],
    rule_text: str,
    rule_label: str,
    init_count: int,
    target_kb: str,
    clear_collection_on_start: bool = False,
    verbose: bool = True,
    task_id: Optional[str] = None,
    frozen_qa_snapshot_xlsx: Optional[Path] = None,
    progress_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
    llm_config_name: Optional[str] = None,
) -> ExtractResult:
    _ = init_count  # 保留参数兼容旧调用；首轮 count 取答案类别数，首轮每类 1 条
    tid = (task_id or "").strip()
    task_id = tid if tid else ("EXT-" + uuid.uuid4().hex[:10].upper())
    collection = collection_name_from_target(target_kb)

    bad = [
        {"id": str(b.get("id") or ""), "query": str(b.get("query") or "").strip(), "answer": str(b.get("answer") or b.get("menu") or "").strip()}
        for b in (badcases or [])
    ]
    bad = [b for b in bad if b["query"] and b["answer"]]
    if not bad:
        return ExtractResult(task_id=task_id, status="failed", collection=collection, target_kb=target_kb, last_error="BadCase 为空或缺少 query/answer")

    round_logs: List[Dict[str, Any]] = []

    cfg = CONFIG.get("rag_extract", {})
    max_rounds = int(cfg.get("max_rounds") or 200)
    _mc = cfg.get("max_count")
    max_count = int(_mc) if _mc is not None and str(_mc).strip() != "" else (len(bad) + 20)
    target_recall = float(cfg.get("target_recall") or 1.0)
    top_k = int(cfg.get("top_k") or 5)
    post_sim_enabled = bool(cfg.get("post_similarity_enabled", True))
    post_sim_top_k = max(1, int(cfg.get("post_similarity_top_k") or 5))
    post_sim_dist_th = float(cfg.get("post_similarity_distance_threshold") or 0.2)
    completion_mode = str(cfg.get("completion_mode") or "llm").strip().lower()
    if completion_mode not in {"llm", "cluster"}:
        completion_mode = "llm"
    chroma_cfg = CONFIG.get("chroma", {})
    write_batch_size = max(1, int(chroma_cfg.get("write_batch_size") or 128))
    query_batch_size = max(1, int(chroma_cfg.get("query_batch_size") or 128))

    # 0) 可选清空测试库（默认不清空）
    if clear_collection_on_start:
        try:
            clear_collection_fully(collection)
        except Exception as e:
            return ExtractResult(
                task_id=task_id,
                status="failed",
                collection=collection,
                target_kb=target_kb,
                last_error=f"清空测试库失败: {e}",
                round_logs=round_logs,
            )

    ordered_answers = ordered_answer_keys(bad)
    answer_to_queries: Dict[str, List[str]] = {}
    for row in bad:
        answer_to_queries.setdefault(row["answer"], []).append(row["query"])

    bad_query_embedding_cache: Dict[str, List[float]] = {}
    unique_bad_queries = sorted({str(b.get("query") or "").strip() for b in bad if str(b.get("query") or "").strip()})
    if unique_bad_queries:
        pre_vecs = embed_texts(unique_bad_queries)
        for q, v in zip(unique_bad_queries, pre_vecs):
            bad_query_embedding_cache[q] = list(v)
        if verbose:
            print(f"[cache] 已预计算 badcase query 向量 {len(bad_query_embedding_cache)} 条")
    last_recall_detail: Dict[str, Dict[str, Any]] = {}
    accumulated_qas: List[Dict[str, Any]] = []
    frozen_answers_done: Set[str] = set()
    iterations: List[ExtractIteration] = []

    # llm 模式调度参数 count：初始为「答案类别数」；首轮与每类 1 条配合，总生成 = len(ordered_answers)
    count = max(1, len(ordered_answers))

    while True:
        round_num = len(iterations) + 1
        if round_num > max_rounds or count > max_count:
            safe_progress(
                progress_callback,
                {
                    "phase": "done",
                    "status": "failed",
                    "reason": "max_rounds_or_count",
                    "qa_pairs_total": len(accumulated_qas),
                    "task_id": task_id,
                },
            )
            return ExtractResult(
                task_id=task_id,
                status="failed",
                collection=collection,
                target_kb=target_kb,
                last_error="未在限定轮次内达到目标召回率",
                iterations=iterations,
                final_qa_pairs=list(accumulated_qas),
                round_logs=round_logs,
            )

        run_tag = f"{task_id}:r{round_num}"
        active, alloc, budget, alloc_mode = prepare_round_allocation(
            ordered_answers=ordered_answers,
            frozen_answers_done=frozen_answers_done,
            completion_mode=completion_mode,
            round_num=round_num,
            count=count,
            badcases=bad,
            accumulated_qas_len=len(accumulated_qas),
            last_recall_detail=last_recall_detail,
        )

        reset_embed_stats()
        round_gen = generate_and_upsert_round(
            active=active,
            alloc=alloc,
            answer_to_queries=answer_to_queries,
            accumulated_qas=accumulated_qas,
            rule_text=rule_text,
            rule_label=rule_label,
            task_id=task_id,
            round_num=round_num,
            completion_mode=completion_mode,
            bad_query_embedding_cache=bad_query_embedding_cache,
            collection=collection,
            run_tag=run_tag,
            write_batch_size=write_batch_size,
            llm_config_name=llm_config_name,
        )
        round_qas_by_answer = round_gen["round_qas_by_answer"]
        round_chroma_ids = round_gen["round_chroma_ids"]
        gen_total = int(round_gen["gen_total"])
        llm_cat = int(round_gen["llm_cat"])
        fb_cat = int(round_gen["fb_cat"])
        cluster_cat = int(round_gen["cluster_cat"])
        cluster_round_detail = dict(round_gen["cluster_round_detail"])

        # 3) 召回评测
        recall_rate, all_ok, detail = evaluate_recall_detail(
            collection_name=collection,
            badcases=bad,
            top_k=top_k,
            completed_answers=frozen_answers_done,
            trace_context={"task_id": task_id, "round_num": round_num},
            badcase_query_embedding_cache=bad_query_embedding_cache,
            query_batch_size=query_batch_size,
        )
        emb_after = embed_stats_snapshot()
        last_recall_detail = dict(detail)

        newly_done = [
            a
            for a, d in detail.items()
            if a not in frozen_answers_done and int(d.get("total") or 0) > 0 and int(d.get("hit") or 0) >= int(d.get("total") or 0)
        ]
        before = len(accumulated_qas)
        for a in newly_done:
            frozen_answers_done.add(a)
            accumulated_qas.extend(round_qas_by_answer.get(a, []))
        retained_qa_this_round = len(accumulated_qas) - before
        iterations.append(ExtractIteration(round_num=round_num, qa_count=retained_qa_this_round, recall_rate=recall_rate))
        frozen_total = len(frozen_answers_done)
        frozen_list = sorted(list(frozen_answers_done))
        newly_done_list = sorted(list(newly_done))

        log_entry = build_round_log_entry(
            round_num=round_num,
            run_tag=run_tag,
            target_recall=target_recall,
            alloc_mode=alloc_mode,
            budget=budget,
            count=count,
            completion_mode=completion_mode,
            gen_total=gen_total,
            round_chroma_ids_len=len(round_chroma_ids),
            llm_cat=llm_cat,
            fb_cat=fb_cat,
            cluster_cat=cluster_cat,
            recall_rate=recall_rate,
            all_ok=all_ok,
            retained_qa_this_round=retained_qa_this_round,
            frozen_total=frozen_total,
            frozen_list=frozen_list,
            newly_done_list=newly_done_list,
            emb_after=emb_after,
        )
        round_logs.append(log_entry)
        append_cluster_trace_if_needed(
            completion_mode=completion_mode,
            active=active,
            cluster_round_detail=cluster_round_detail,
            task_id=task_id,
            round_num=round_num,
            collection=collection,
            gen_total=gen_total,
            recall_rate=recall_rate,
            all_ok=all_ok,
        )
        if frozen_qa_snapshot_xlsx is not None:
            try:
                write_frozen_qa_xlsx(frozen_qa_snapshot_xlsx, accumulated_qas)
            except Exception:
                pass
        safe_progress(
            progress_callback,
            {
                "phase": "round",
                "round": round_num,
                "recall_rate": recall_rate,
                "all_ok": all_ok,
                "qa_pairs_total": len(accumulated_qas),
                "qa_pairs_round_delta": retained_qa_this_round,
                "frozen_answer_categories": frozen_total,
                "target_recall": target_recall,
                "gen_total": gen_total,
            },
        )
        if verbose:
            print_round_verbose(
                round_num=round_num,
                completion_mode=completion_mode,
                alloc_mode=alloc_mode,
                budget=budget,
                count=count,
                target_recall=target_recall,
                active=active,
                alloc=alloc,
                gen_total=gen_total,
                round_chroma_ids_len=len(round_chroma_ids),
                llm_cat=llm_cat,
                fb_cat=fb_cat,
                cluster_cat=cluster_cat,
                recall_rate=recall_rate,
                all_ok=all_ok,
                retained_qa_this_round=retained_qa_this_round,
                frozen_total=frozen_total,
                newly_done_list=newly_done_list,
                frozen_list=frozen_list,
                emb_after=emb_after,
            )

        # 4) 判断成功
        if recall_rate >= target_recall and all_ok:
            # 清理本轮临时向量（与项目逻辑一致）
            rollback_n = _delete_round_vectors(collection, round_chroma_ids)
            if verbose:
                print(f"  成功收尾: 回滚删除本轮临时向量 {rollback_n} 条（与临时入库数一致）")
            log_entry["rollback_deleted"] = rollback_n
            log_entry["outcome"] = "success_cleanup"
            safe_progress(
                progress_callback,
                {
                    "phase": "done",
                    "status": "success",
                    "qa_pairs_total": len(accumulated_qas),
                    "round": round_num,
                    "recall_rate": recall_rate,
                    "task_id": task_id,
                },
            )
            return ExtractResult(
                task_id=task_id,
                status="success",
                collection=collection,
                target_kb=target_kb,
                iterations=iterations,
                final_qa_pairs=attach_high_similarity_hits(
                    collection=collection,
                    qa_pairs=list(accumulated_qas),
                    top_k=post_sim_top_k if post_sim_enabled else 0,
                    distance_threshold=post_sim_dist_th,
                    query_batch_size=query_batch_size,
                ),
                round_logs=round_logs,
            )

        # 5) 回滚（删除本轮临时入库向量）
        rollback_n = _delete_round_vectors(collection, round_chroma_ids)
        if verbose:
            print(f"  未达标: 回滚删除本轮临时向量 {rollback_n} 条")
        log_entry["rollback_deleted"] = rollback_n
        log_entry["outcome"] = "rollback_continue"

        count = _advance_generation_strategy(
            count=count,
            top_k=top_k,
            ordered_answers=ordered_answers,
            frozen_answers_done=frozen_answers_done,
        )

