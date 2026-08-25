from __future__ import annotations

import hashlib
import re
import uuid


def content_hash_id(question: str, intent_name: str) -> str:
    """相同相似问+意图名称 → 相同 ID，重复导入时 upsert 覆盖而非新增冲突。"""
    key = f"{question.strip()}|{intent_name.strip()}".encode("utf-8")
    return "kb_" + hashlib.sha256(key).hexdigest()[:32]


def generate_record_id(
    question: str,
    intent_name: str,
    row_index: int,
    strategy: str = "uuid",
    explicit_id: str = None,
) -> str:
    """
    生成语料 ID。
      - uuid: 每条新 UUID（推荐生产入库）
      - content_hash: 由相似问+意图名称确定性生成，适合幂等更新
      - row: row_N（仅调试）
    """
    if explicit_id:
        return explicit_id.strip()
    if strategy == "row":
        return f"row_{row_index}"
    if strategy == "content_hash":
        return content_hash_id(question, intent_name)
    return "kb_" + uuid.uuid4().hex
