# coding: utf-8
"""快照判据的收集条件：存量副本在场才收，不在场整体跳过、不静默通过。

本目录的 `test_*_snapshot.py` 拿存量代码（`.legacy-oracle/applications/a2a_service` 的
`common`／`api`／`channels`／`orchestrator`）对转录件 `frozen_facts.py` 逐条比——它们是
「存量代码 → 转录件」这一环唯一的机器判据。2026-08-26 检察官发现本目录从未进 pytest 收集面，
这一环此前零执行。

存量副本由 `tools/legacy_oracle.sh` 按锚定提交临时导出；未导出时把本目录的判据文件整体
列入 `collect_ignore`，与 `agent_runtime/tests/conftest.py` 对依赖存量的判据的处置同形。
"""

from __future__ import annotations

import pathlib
import sys

_HERE = pathlib.Path(__file__).resolve().parent
_ORACLE_BASE = _HERE.parents[1] / ".legacy-oracle"
_ORACLE = _ORACLE_BASE / "applications" / "a2a_service"

if _ORACLE.is_dir():
    for _root in (_ORACLE_BASE, _ORACLE, _ORACLE_BASE / "foundation", _ORACLE_BASE / "service"):
        if _root.is_dir() and str(_root) not in sys.path:
            sys.path.append(str(_root))
    collect_ignore: list[str] = []
else:
    collect_ignore = sorted(p.name for p in _HERE.glob("test_*.py"))
