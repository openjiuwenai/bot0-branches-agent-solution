from __future__ import annotations

from typing import Any, Dict

CORPUS_BATCH = "batch"
CORPUS_PRODUCTION = "production"

_CORPUS_LABEL = {
    CORPUS_BATCH: "本批语料",
    CORPUS_PRODUCTION: "知识库已有语料",
}


def corpus_label(scope: str) -> str:
    return _CORPUS_LABEL.get(scope, "未知来源语料")


def format_row_ref(row_index: int) -> str:
    """当前质检条在输入 CSV 中的行号。"""
    return f"第{int(row_index)}行"


def batch_hit_row_label(hit: Dict[str, Any]) -> str:
    """本批命中语料的 Excel 行号标签，如「第3行」。"""
    if hit.get("corpus_scope") != CORPUS_BATCH:
        return ""
    row_index = hit.get("row_index")
    if row_index is None or row_index == "":
        return ""
    try:
        return format_row_ref(int(row_index))
    except (TypeError, ValueError):
        return ""


def format_hit_ref(hit: Dict[str, Any]) -> str:
    """本批语料用行号，知识库用 Chroma 文档 ID。"""
    row_label = batch_hit_row_label(hit)
    if row_label:
        return row_label
    scope = hit.get("corpus_scope", "")
    if scope == CORPUS_BATCH:
        return hit.get("id", "")
    return hit.get("id", "")


def normalize_similarity_hit(hit: Dict[str, Any]) -> Dict[str, Any]:
    """预查缓存前规范化命中字段，确保 row_index 等可读信息保留。"""
    out = dict(hit)
    row_index = out.get("row_index")
    if row_index is not None and row_index != "":
        try:
            out["row_index"] = int(row_index)
        except (TypeError, ValueError):
            out["row_index"] = None
    return out


def enrich_hit(hit: Dict[str, Any]) -> Dict[str, Any]:
    """为 evidence 补充可读来源字段。"""
    out = dict(hit)
    out["corpus_label"] = corpus_label(hit.get("corpus_scope", ""))
    out["display_ref"] = format_hit_ref(hit)
    return out


def duplicate_rule_id(scope: str) -> str:
    return "DUPLICATE_BATCH_001" if scope == CORPUS_BATCH else "DUPLICATE_KB_001"


def conflict_rule_id(scope: str) -> str:
    return "CONFLICT_BATCH_001" if scope == CORPUS_BATCH else "CONFLICT_KB_001"


def duplicate_reason(hit: Dict[str, Any]) -> str:
    row_label = batch_hit_row_label(hit)
    if row_label:
        base = f"与{row_label}重复"
    else:
        label = corpus_label(hit.get("corpus_scope", ""))
        base = f"与{label}重复"
    base += (
        f"（相似度 {hit['similarity']:.2f}："
        f"「{hit['document']}」→ {hit['intent_name']}）"
    )
    return base


def duplicate_suggestion(scope: str) -> str:
    if scope == CORPUS_BATCH:
        return "与本批其他相似问重复，建议合并或删除本条"
    return "与知识库同意图名称问法重复，建议合并或丢弃本条"


def conflict_reason(hit: Dict[str, Any], intent_name: str) -> str:
    row_label = batch_hit_row_label(hit)
    if row_label:
        base = f"与{row_label}冲突"
    else:
        label = corpus_label(hit.get("corpus_scope", ""))
        base = f"与{label}冲突"
    base += (
        f"（相似度 {hit['similarity']:.2f}："
        f"「{hit['document']}」→ {hit['intent_name']}，"
        f"本条标注为「{intent_name}」）"
    )
    return base


def conflict_suggestion(scope: str) -> str:
    if scope == CORPUS_BATCH:
        return "与本批其他条存在意图冲突，需人工裁定归属或调整标注"
    return "与知识库已有条存在意图冲突，需人工裁定正确归属意图名称"
