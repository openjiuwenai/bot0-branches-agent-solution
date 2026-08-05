"""SQL 危险语句拦截 — 黑名单关键字 + 危险模式检测。"""

from __future__ import annotations

import re
from dataclasses import dataclass

from ..config import SecurityConfig


@dataclass
class GuardResult:
    """拦截结果。"""
    allowed: bool
    reason: str = ""


class SqlGuard:
    """SQL 守卫：在执行前拦截危险语句。"""

    # 无 WHERE 条件的 UPDATE/DELETE
    _NO_WHERE_RE = re.compile(
        r"^\s*(UPDATE\s+\w+\s+SET|DELETE\s+FROM\s+\w+)\s+(?!.*\bWHERE\b)",
        re.IGNORECASE | re.DOTALL,
    )

    def __init__(self, config: SecurityConfig) -> None:
        self._blocked_keywords: list[str] = [kw.upper() for kw in config.blocked_keywords]
        self._allow_ddl: bool = config.allow_ddl
        self._max_write_rows: int = 1000

    def check(self, sql_template: str, mode: str) -> GuardResult:
        """检查 SQL 模板是否允许执行。

        Args:
            sql_template: 含占位符的 SQL 模板
            mode: 当前运行模式 readonly | readwrite | ddl
        """
        upper = sql_template.upper()

        # 1. 黑名单关键字
        for kw in self._blocked_keywords:
            if kw in upper:
                return GuardResult(allowed=False, reason=f"命中黑名单关键字: {kw}")

        # 2. 注释注入特征
        if "--" in sql_template or "/*" in sql_template:
            return GuardResult(allowed=False, reason="检测到注释注入特征")

        # 3. 堆叠注入（分号后跟第二条语句）
        stripped = sql_template.strip().rstrip(";")
        if ";" in stripped:
            return GuardResult(allowed=False, reason="检测到堆叠注入（多语句）")

        # 4. DDL 检查
        is_ddl = bool(re.search(r"\b(CREATE|ALTER|DROP|TRUNCATE)\b", upper))
        if is_ddl and not self._allow_ddl:
            return GuardResult(allowed=False, reason="DDL 操作未启用（allow-ddl=false）")
        if is_ddl and mode != "ddl":
            return GuardResult(allowed=False, reason=f"当前模式 {mode} 不允许 DDL")

        # 5. 写操作模式检查
        is_write = bool(re.search(r"\b(INSERT|UPDATE|DELETE)\b", upper))
        if is_write and mode == "readonly":
            return GuardResult(allowed=False, reason="只读模式不允许写操作")

        # 6. 无 WHERE 的 UPDATE/DELETE
        if self._NO_WHERE_RE.search(sql_template):
            return GuardResult(allowed=False, reason="UPDATE/DELETE 缺少 WHERE 条件")

        return GuardResult(allowed=True)
