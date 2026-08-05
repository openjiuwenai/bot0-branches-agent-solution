"""SQL 语句归一化与指纹 — 用于审计日志与模板白名单。"""

from __future__ import annotations

import hashlib
import re


class StatementNormalizer:
    """将 SQL 模板归一化并生成指纹哈希。"""

    # 匹配占位符（%s, ?, :name）统一替换为 ?
    _PLACEHOLDER_RE = re.compile(r"(%s|\?|:\w+)")
    # 连续空白压缩
    _WS_RE = re.compile(r"\s+")

    @classmethod
    def normalize(cls, sql_template: str) -> str:
        """归一化：占位符统一为 ?，空白压缩，转大写关键字。"""
        normalized = cls._PLACEHOLDER_RE.sub("?", sql_template)
        normalized = cls._WS_RE.sub(" ", normalized).strip()
        return normalized

    @classmethod
    def fingerprint(cls, sql_template: str) -> str:
        """生成 SQL 模板的 SHA-256 指纹（前 16 位）。"""
        normalized = cls.normalize(sql_template)
        return hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:16]
