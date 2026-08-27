from __future__ import annotations

from typing import Callable, List, Optional

from backend.knowledge_qc.checkers.base import CheckContext
from backend.knowledge_qc.checkers.corpus_scope import normalize_similarity_hit
from backend.knowledge_qc.checkers.hit_filters import filter_hits_for_record
from backend.knowledge_qc.models import QARecord


def similarity_query_batch_size(settings: dict) -> int:
    """向量预查分批大小（rules.yaml → similarity.query_batch_size）。"""
    rules = settings.get("rules", {})
    return max(
        1,
        int(rules.get("similarity", {}).get("query_batch_size", 32)),
    )


def similarity_prefilter_threshold(rules: dict) -> Optional[float]:
    """重复/冲突检测启用时，预查阶段使用的最低相似度阈值。"""
    chk = rules.get("checkers", {})
    dup_on = bool(chk.get("duplicate"))
    conflict_on = bool(chk.get("conflict"))
    if not dup_on and not conflict_on:
        return None

    sim = rules.get("similarity", {})
    thresholds: List[float] = []
    if dup_on:
        thresholds.append(float(sim.get("duplicate_threshold", 0.92)))
    if conflict_on:
        thresholds.append(float(sim.get("conflict_threshold", 0.78)))
    return min(thresholds)


def preload_similarity_hits(
    records: List[QARecord],
    ctx: CheckContext,
    *,
    query_batch_size: int = 32,
    on_log: Optional[Callable[[str], None]] = None,
    should_cancel: Optional[Callable[[], bool]] = None,
) -> int:
    """批量查询向量库，按阈值过滤后写入 ctx.similarity_hits_cache。"""
    threshold = similarity_prefilter_threshold(ctx.rules)
    if threshold is None or not records:
        return 0

    top_k = int(ctx.rules.get("similarity", {}).get("top_k", 5))
    mode = ctx.rules.get("detection_mode", {})
    search_production = bool(mode.get("production", True))
    search_staging = bool(mode.get("batch", True))
    batch_size = max(1, int(query_batch_size))

    total_hits = 0
    for start in range(0, len(records), batch_size):
        if should_cancel and should_cancel():
            break
        chunk = records[start:start + batch_size]
        embeddings = [ctx.get_embedding(r.question) for r in chunk]
        exclude_ids = [r.record_id for r in chunk]
        batch_hits = ctx.vector_store.query_both_batch(
            embeddings,
            top_k,
            exclude_ids=exclude_ids,
            search_production=search_production,
            search_staging=search_staging,
        )
        for record, hits in zip(chunk, batch_hits):
            filtered = filter_hits_for_record(hits, record)
            filtered = [
                normalize_similarity_hit(h)
                for h in filtered
                if h["similarity"] >= threshold
            ]
            ctx.similarity_hits_cache[record.record_id] = filtered
            total_hits += len(filtered)

    ctx.similarity_hits_preloaded = True
    if on_log:
        on_log(
            f"  完成：{len(records)} 条预查，"
            f"相似度 ≥ {threshold:.2f} 的命中共 {total_hits} 条"
        )
    return total_hits


def restore_similarity_hits_cache(
    raw: Dict[str, List[Dict[str, Any]]],
) -> Dict[str, List[Dict[str, Any]]]:
    """从 JSON 报告恢复重复/冲突预查缓存。"""
    out: Dict[str, List[Dict[str, Any]]] = {}
    for record_id, hits in (raw or {}).items():
        rid = str(record_id or "").strip()
        if not rid:
            continue
        out[rid] = [
            normalize_similarity_hit(dict(h))
            for h in (hits or [])
            if isinstance(h, dict)
        ]
    return out


def count_similarity_hits(cache: Dict[str, List[Dict[str, Any]]]) -> int:
    return sum(len(v) for v in (cache or {}).values())
