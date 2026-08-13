# -*- coding: utf-8 -*-
"""边界回归护栏。

DB 化后收敛两条边界：
  1. calc_service 全部源文件（排除 tests 自身）不得再出现任何 Excel 访问痕迹——
     pd.read_excel( / pd.ExcelFile( / openpyxl / ExcelStore / excel_store / read_sheet
     均视为破口。Excel 已彻底弃用，DB 为唯一存储。
  2. service / scheduling / calculation / api 层不得直接持有或创建 DB engine/session，
     必须经 data 层 Repository 或 get_session()（依赖方向：上层 → data → DB）。
     仅 data/ 与 migrate_excel_to_db.py / _diag_* 诊断脚本允许直接碰 engine。
"""
import os
from pathlib import Path

import calc_service.backend as calc_service

_SOLVE_V1_ROOT = Path(os.path.dirname(os.path.abspath(calc_service.__file__)))
# Excel 访问的禁止 token：任一出现即视为绕过 DB 的破口
_EXCEL_TOKENS = (
    "pd.read_excel(", "pd.ExcelFile(", "openpyxl", "ExcelStore",
    "excel_store", ".read_sheet(",
)

# 允许直接使用 engine/SessionLocal 的模块（data 层 + 迁移/诊断脚本）
_DB_ENGINE_OK_PREFIXES = ("data",)
_DB_ENGINE_OK_FILES = {"migrate_excel_to_db.py", "_diag_cjy_io.py", "e2e_test.py"}
# 允许残留 Excel 访问的模块（仅一次性迁移脚本读历史 Excel）
_EXCEL_OK_FILES = {"migrate_excel_to_db.py"}
# 直接创建 engine / 自行 create_engine 的禁止 token（出现在非白名单模块即破口）
_DB_ENGINE_TOKENS = ("create_engine(", "engine.connect(", "SessionLocal()")


def _source_files():
    """calc_service 下所有 .py（排除 tests/ 与 __pycache__）。"""
    out = []
    for p in sorted(_SOLVE_V1_ROOT.rglob("*.py")):
        rel = p.relative_to(_SOLVE_V1_ROOT).as_posix()
        if rel.startswith("tests/"):
            continue
        if "__pycache__" in p.parts:
            continue
        out.append(p)
    return out


def _strip_comments_and_docstrings(text: str) -> str:
    """粗略剔除注释行与三引号块，避免 docstring 里提到 API 名误报。"""
    lines = text.splitlines()
    out = []
    in_block = False
    for line in lines:
        stripped = line.lstrip()
        if in_block:
            if '"""' in stripped or "'''" in stripped:
                in_block = False
            continue
        if stripped.startswith("#"):
            continue
        if stripped.startswith('"""') or stripped.startswith("'''"):
            if stripped.count('"""') < 2 and stripped.count("'''") < 2:
                in_block = True
            continue
        out.append(line)
    return "\n".join(out)


def test_no_excel_access_in_calc_service():
    """任何源文件不得残留 Excel 访问（DB 为唯一存储）。"""
    offenders = []
    for path in _source_files():
        rel = path.relative_to(_SOLVE_V1_ROOT).as_posix()
        if rel in _EXCEL_OK_FILES:
            continue
        text = _strip_comments_and_docstrings(path.read_text(encoding="utf-8"))
        for token in _EXCEL_TOKENS:
            if token in text:
                for lineno, line in enumerate(text.splitlines(), 1):
                    if token in line:
                        offenders.append(f"{rel}:{lineno}: {line.strip()} [token={token}]")
                        break
    assert not offenders, (
        "发现残留 Excel 访问（DB 化后应彻底弃用 Excel，改走 RefineryRepository/"
        "SchedulingRepository）：\n" + "\n".join(offenders)
    )


def test_no_direct_db_engine_outside_data_layer():
    """service/scheduling/calculation/api 不得直接 create_engine 或 new SessionLocal。

    上层只能经 data 层 Repository 或 get_session() 拿 session；engine 创建与
    SessionLocal 实例化是 data 层独有职责。白名单：data/ 子包、迁移与诊断脚本。
    """
    offenders = []
    for path in _source_files():
        rel = path.relative_to(_SOLVE_V1_ROOT).as_posix()
        top = rel.split("/")[0]
        if top in _DB_ENGINE_OK_PREFIXES or rel in _DB_ENGINE_OK_FILES:
            continue
        text = _strip_comments_and_docstrings(path.read_text(encoding="utf-8"))
        for token in _DB_ENGINE_TOKENS:
            if token in text:
                for lineno, line in enumerate(text.splitlines(), 1):
                    if token in line:
                        offenders.append(f"{rel}:{lineno}: {line.strip()} [token={token}]")
                        break
    assert not offenders, (
        "上层模块不得直接创建 engine/session（应经 data 层 Repository 或 get_session()）：\n"
        + "\n".join(offenders)
    )


def test_service_layer_does_not_import_flask():
    """service 层不得依赖 Flask（保持可独立测试、单向依赖）。"""
    service_dir = _SOLVE_V1_ROOT / "service"
    for path in sorted(service_dir.glob("*.py")):
        text = path.read_text(encoding="utf-8")
        assert "from flask" not in text and "import flask" not in text, (
            f"{path.name} 不应依赖 Flask（service 返回普通 dict，由路由 jsonify）"
        )
