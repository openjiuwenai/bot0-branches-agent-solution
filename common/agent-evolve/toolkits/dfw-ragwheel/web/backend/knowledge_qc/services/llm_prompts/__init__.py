"""LLM 质检提示词（相似问语义 / 意图描述）。"""

from backend.knowledge_qc.services.llm_prompts.dup_conflict import (
    DUP_CONFLICT_BATCH_DEFAULT,
    DUP_CONFLICT_DEFAULT,
    build_dup_conflict_http_question,
    build_dup_conflict_openai_messages,
    format_dup_conflict_batch_prompt,
    normalize_dup_conflict_batch_result,
    normalize_dup_conflict_result,
)
from backend.knowledge_qc.services.llm_prompts.intent_desc import (
    INTENT_DESC_DEFAULT,
    INTENT_DESC_SYSTEM,
    build_intent_desc_http_question,
    build_intent_desc_openai_messages,
    format_intent_desc_prompt,
    normalize_intent_desc_result,
)
from backend.knowledge_qc.services.llm_prompts.json_util import parse_json_object
from backend.knowledge_qc.services.llm_prompts.semantic import (
    DEFAULT_RESULT,
    build_http_question,
    build_openai_messages,
    format_semantic_prompt,
    normalize_result,
)

__all__ = [
    "DEFAULT_RESULT",
    "DUP_CONFLICT_BATCH_DEFAULT",
    "DUP_CONFLICT_DEFAULT",
    "INTENT_DESC_DEFAULT",
    "INTENT_DESC_SYSTEM",
    "build_dup_conflict_http_question",
    "build_dup_conflict_openai_messages",
    "build_http_question",
    "build_intent_desc_http_question",
    "build_intent_desc_openai_messages",
    "build_openai_messages",
    "format_dup_conflict_batch_prompt",
    "format_intent_desc_prompt",
    "format_semantic_prompt",
    "normalize_dup_conflict_batch_result",
    "normalize_dup_conflict_result",
    "normalize_intent_desc_result",
    "normalize_result",
    "parse_json_object",
]
