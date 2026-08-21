from __future__ import annotations

from backend.knowledge_qc.checkers.base import CheckContext
from backend.knowledge_qc.checkers.corpus_scope import format_row_ref
from backend.knowledge_qc.checkers.llm_guard import call_llm
from backend.knowledge_qc.models import QARecord


def llm_dup_conflict_enabled(ctx: CheckContext) -> bool:
    chk = ctx.rules.get("checkers", {})
    if not ctx.llm:
        return False
    if not chk.get("llm_dup_conflict", False):
        return False
    return bool(chk.get("duplicate")) or bool(chk.get("conflict"))


def _hit_cache_key(record: QARecord, hit: dict, relation: str) -> str:
    hit_id = hit.get("id") or ""
    return f"{record.record_id}|{hit_id}|{relation}"


def _log_llm_hit_result(
    ctx: CheckContext,
    record: QARecord,
    hit: dict,
    relation: str,
    result: dict,
) -> None:
    if not ctx.on_log:
        return
    label = "重复" if relation == "duplicate" else "冲突"
    confirmed = bool(result.get("confirmed", False))
    verdict = "成立" if confirmed else "不成立"
    sim = float(hit.get("similarity", 0))
    cur_q = (record.question or "").strip()
    hit_q = (hit.get("document") or "").strip()
    reason = str(result.get("reason") or "").strip()
    msg = (
        f"      [LLM {label}] {format_row_ref(record.row_index)} "
        f"相似度 {sim:.2f} → {verdict} | "
        f"「{cur_q}」→{record.intent_name} vs "
        f"「{hit_q}」→{hit.get('intent_name') or ''}"
    )
    if reason:
        msg += f" | {reason}"
    ctx.on_log(msg)


def _store_hit_judgment(
    ctx: CheckContext,
    record: QARecord,
    hit: dict,
    relation: str,
    jresult: dict,
) -> bool:
    key = _hit_cache_key(record, hit, relation)
    confirmed = bool(jresult.get("confirmed", False))
    reason = str(jresult.get("reason") or "").strip()
    with ctx.cache_lock:
        ctx.llm_hit_cache[key] = {"confirmed": confirmed, "reason": reason}
    _log_llm_hit_result(ctx, record, hit, relation, jresult)
    if confirmed:
        hit["llm_reason"] = reason
    return confirmed


def resolve_dup_conflict_issue_reason(
    base_reason: str,
    record: QARecord,
    hit: dict,
    relation: str,
    ctx: CheckContext,
) -> str:
    """将重复/冲突 LLM 裁决理由写入不通过原因（优先读 llm_hit_cache）。"""
    if not llm_dup_conflict_enabled(ctx):
        return base_reason
    key = _hit_cache_key(record, hit, relation)
    with ctx.cache_lock:
        entry = ctx.llm_hit_cache.get(key) or {}
    if not entry.get("confirmed"):
        return base_reason
    llm_reason = str(entry.get("reason") or hit.get("llm_reason") or "").strip()
    if not llm_reason:
        return base_reason
    if "LLM判定" in base_reason:
        return base_reason
    return f"{base_reason}；LLM判定：{llm_reason}"


def llm_filter_confirmed_hits(
    record: QARecord,
    hits: list,
    relation: str,
    ctx: CheckContext,
) -> list:
    """一次 LLM 调用裁决多条向量命中，返回 confirmed=true 的子集。"""
    if not hits:
        return []
    if not llm_dup_conflict_enabled(ctx):
        return hits

    cached: list[dict | None] = []
    with ctx.cache_lock:
        for hit in hits:
            key = _hit_cache_key(record, hit, relation)
            cached.append(ctx.llm_hit_cache.get(key))

    if all(v is not None for v in cached):
        confirmed_hits: list = []
        for hit, entry in zip(hits, cached):
            if not entry or not entry.get("confirmed"):
                continue
            hit["llm_reason"] = str(entry.get("reason") or "").strip()
            confirmed_hits.append(hit)
        return confirmed_hits

    result = call_llm(
        lambda: ctx.llm.judge_dup_conflict_hits(
            record.question,
            record.intent_name,
            hits,
            relation,
        )
    )
    judgments = result.get("judgments") or []
    confirmed_hits = []
    for i, hit in enumerate(hits):
        jresult = (
            judgments[i]
            if i < len(judgments)
            else {"confirmed": False, "reason": ""}
        )
        if _store_hit_judgment(ctx, record, hit, relation, jresult):
            confirmed_hits.append(hit)
    return confirmed_hits
