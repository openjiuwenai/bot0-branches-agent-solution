"""SQL 标识符白名单校验 — 防止表名/列名注入。"""

from __future__ import annotations

import re

# 合法标识符：字母/下划线开头，长度 1-64，仅含字母数字下划线
_IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")

# SQL 注入常见特征（出现在标识符位置时一律拒绝）
_INJECTION_PATTERNS = [
    re.compile(r"--"),           # 行注释
    re.compile(r"/\*.*\*/"),     # 块注释
    re.compile(r";"),            # 语句分隔
    re.compile(r"\bUNION\b", re.I),
    re.compile(r"\bSELECT\b", re.I),
    re.compile(r"\bINSERT\b", re.I),
    re.compile(r"\bUPDATE\b", re.I),
    re.compile(r"\bDELETE\b", re.I),
    re.compile(r"\bDROP\b", re.I),
    re.compile(r"\bEXEC\b", re.I),
    re.compile(r"\bxp_", re.I),
]


def validate_identifier(identifier: str) -> str:
    """校验单个标识符（表名/列名），通过则原样返回，否则抛出 ValueError。"""
    if not identifier or not _IDENTIFIER_RE.match(identifier):
        raise ValueError(f"非法标识符: {identifier!r}")
    for pat in _INJECTION_PATTERNS:
        if pat.search(identifier):
            raise ValueError(f"标识符含注入特征: {identifier!r}")
    return identifier


class SqlSanitizer:
    """SQL 标识符校验器，结合白名单做双重过滤。"""

    def __init__(self, allowed_tables: list[str] | None = None) -> None:
        self._allowed: set[str] = set(allowed_tables) if allowed_tables else set()

    def validate_table(self, table_name: str) -> str:
        """校验表名：先过正则，再查白名单（若白名单非空）。"""
        validated = validate_identifier(table_name)
        if self._allowed and validated not in self._allowed:
            raise ValueError(f"表 {validated!r} 不在白名单中")
        return validated

    def validate_column(self, column_name: str) -> str:
        """校验列名：仅过正则白名单。"""
        return validate_identifier(column_name)

    def is_safe_identifier(self, identifier: str) -> bool:
        """非抛出式检查，返回 True/False。"""
        try:
            validate_identifier(identifier)
            return True
        except ValueError:
            return False
