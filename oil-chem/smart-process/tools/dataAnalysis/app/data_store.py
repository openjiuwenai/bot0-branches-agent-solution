"""数据源持久化：每个装置模块支持天级/小时级两种粒度，各自独立存储。

存储内容：经预处理的长表 DataFrame（validator._prepare_long_df 的输出）+ 元数据。
宽表视图在需要时从长表重新透视生成，保证预览与导出一致、并自然处理字段变化。
"""
from __future__ import annotations

import pickle
from datetime import datetime
from pathlib import Path
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)

# 装置模块（每个模块对应页面上一个导入卡片）——持久化，支持动态添加/重命名
_DEFAULT_MODULES: List[Dict[str, str]] = [
    {"id": "2_hydrocracking", "name": "2#加氢裂化"},
    {"id": "dcc", "name": "DCC催化裂解"},
]

def _modules_path() -> Path:
    return DATA_DIR / "modules.pkl"

def _load_modules() -> List[Dict[str, str]]:
    p = _modules_path()
    if p.exists():
        try:
            with open(p, "rb") as f:
                data = pickle.load(f)
            if isinstance(data, list) and data:
                return data
        except Exception:
            pass
    return list(_DEFAULT_MODULES)

def _save_modules(modules: List[Dict[str, str]]) -> None:
    with open(_modules_path(), "wb") as f:
        pickle.dump(modules, f)

def add_module(name: str) -> Dict[str, str]:
    """添加新模块，自动生成唯一 id（不影响已有数据）。"""
    import re
    name = (name or "").strip()
    if not name:
        raise ValueError("模块名称不能为空")
    mods = _load_modules()
    if any(m["name"] == name for m in mods):
        raise ValueError("模块名称已存在：" + name)
    # 基于 name 生成 id：取拼音首字母不可靠，用 m_ + 序号保证唯一
    base = re.sub(r"[^a-zA-Z0-9]", "_", name).strip("_").lower()
    existing_ids = {m["id"] for m in mods}
    if not base:
        k = 1
        while ("mod_" + str(k)) in existing_ids:
            k += 1
        mid = "mod_" + str(k)
    else:
        mid = base
        n = 2
        while mid in existing_ids:
            mid = base + "_" + str(n)
            n += 1
    new_m = {"id": mid, "name": name}
    mods.append(new_m)
    _save_modules(mods)
    _refresh_module_globals()
    return new_m

def rename_module(module_id: str, new_name: str) -> Dict[str, str]:
    """重命名模块（仅改显示名，id 不变，不影响已导入数据）。"""
    new_name = (new_name or "").strip()
    if not new_name:
        raise ValueError("模块名称不能为空")
    mods = _load_modules()
    for m in mods:
        if m["id"] != module_id and m["name"] == new_name:
            raise ValueError("模块名称已存在：" + new_name)
    for m in mods:
        if m["id"] == module_id:
            m["name"] = new_name
            _save_modules(mods)
            _refresh_module_globals()
            return m
    raise ValueError("模块不存在：" + module_id)

def _refresh_module_globals():
    """刷新全局 MODULES / SOURCES，供其它函数引用。"""
    global MODULES, SOURCES
    MODULES = _load_modules()
    SOURCES = []
    for _m in MODULES:
        for _g in GRANULARITIES:
            SOURCES.append({
                "id": _m["id"] + _g["suffix"],
                "name": _m["name"] + "（" + _g["name"] + "）",
                "module_id": _m["id"],
                "granularity": _g["id"],
            })

MODULES: List[Dict[str, str]] = _load_modules()

# 数据粒度
GRANULARITIES: List[Dict[str, str]] = [
    {"id": "daily", "name": "天级", "suffix": ""},
    {"id": "hourly", "name": "小时级", "suffix": "_hourly"},
]

_GRAN_MAP = {g["id"]: g for g in GRANULARITIES}
_GRAN_NAME = {g["id"]: g["name"] for g in GRANULARITIES}

# 兼容旧代码：展开为扁平源列表（模块 × 粒度）
SOURCES: List[Dict[str, str]] = []
_refresh_module_globals()


def module_ids() -> List[str]:
    return [m["id"] for m in MODULES]


def module_name(module_id: str) -> str:
    for m in MODULES:
        if m["id"] == module_id:
            return m["name"]
    return module_id


def granularity_source_id(module_id: str, granularity_id: str) -> str:
    """根据模块 id 和粒度 id 拼出实际存储 source_id。"""
    g = _GRAN_MAP.get(granularity_id)
    return module_id + (g["suffix"] if g else "")


def granularity_name(granularity_id: str) -> str:
    return _GRAN_NAME.get(granularity_id, granularity_id)


def source_ids() -> List[str]:
    return [s["id"] for s in SOURCES]


def source_name(source_id: str) -> str:
    for s in SOURCES:
        if s["id"] == source_id:
            return s["name"]
    return source_id


def source_module_id(source_id: str) -> str:
    for s in SOURCES:
        if s["id"] == source_id:
            return s["module_id"]
    return source_id


def source_granularity(source_id: str) -> str:
    for s in SOURCES:
        if s["id"] == source_id:
            return s["granularity"]
    return "daily"


def _store_path(source_id: str) -> Path:
    return DATA_DIR / f"source_{source_id}.pkl"


def load_store(source_id: str) -> Optional[Dict[str, Any]]:
    p = _store_path(source_id)
    if not p.exists():
        return None
    try:
        with open(p, "rb") as f:
            data = pickle.load(f)
        if isinstance(data, dict):
            return data
        return None
    except Exception:
        return None


def _save_store(source_id: str, store: Dict[str, Any]) -> None:
    with open(_store_path(source_id), "wb") as f:
        pickle.dump(store, f)


def merge_long_df(existing: Optional[pd.DataFrame], new: pd.DataFrame,
                  time_col: str) -> pd.DataFrame:
    """累积合并长表：相同标识（时间 + 非数值描述列）取新导入值，保留首次出现顺序。

    新数据缺少的列（如气体密度）不从已有数据中删除，保留已有值。
    """
    if existing is None or existing.empty:
        return new.copy().reset_index(drop=True)
    if new is None or new.empty:
        return existing.copy().reset_index(drop=True)

    # 保留已有数据中存在但新数据中不存在的列，避免被 NaN 覆盖
    existing_only_cols = [c for c in existing.columns if c not in new.columns]

    combined = pd.concat([existing, new], ignore_index=True)
    id_cols = [c for c in combined.columns
               if c == time_col or not pd.api.types.is_numeric_dtype(combined[c])]
    if not id_cols:
        return combined.drop_duplicates(keep="last").reset_index(drop=True)
    order_df = combined.drop_duplicates(subset=id_cols, keep="first")[id_cols].reset_index(drop=True)
    latest = combined.drop_duplicates(subset=id_cols, keep="last").reset_index(drop=True)
    merged = order_df.merge(latest, on=id_cols, how="left")

    # 对已有数据中独有的列，用已有数据的值回填（新数据导致的 NaN 不覆盖已有值）
    if existing_only_cols:
        existing_latest = existing.drop_duplicates(subset=id_cols, keep="last").reset_index(drop=True)
        for col in existing_only_cols:
            if col in id_cols:
                continue
            col_map = existing_latest.set_index(id_cols)[col]
            fill_vals = merged.set_index(id_cols).index.map(
                lambda x: col_map.get(x) if x in col_map.index else None)
            # Only fill where current value is NaN
            na_mask = merged[col].isna()
            if na_mask.any():
                merged.loc[na_mask, col] = [fill_vals[i] for i in range(len(fill_vals)) if na_mask.iloc[i]]

    return merged.reset_index(drop=True)


def save_import(source_id: str, long_df: pd.DataFrame, time_col: str,
                flow_col: str, mapping: Dict[str, str],
                original_columns: List[str],
                value_cols: Optional[List[str]] = None) -> Dict[str, Any]:
    """将新导入的长表累积合并到数据源存储，返回更新后的 store。

    value_cols 为用户确认的数值列；存入 store 供宽表透视复用，避免源数据中
    的计算列被误识别为数值列。
    """
    store = load_store(source_id) or {}
    existing = store.get("long_df")
    merged = merge_long_df(existing, long_df, time_col)
    import_count = int(store.get("import_count", 0)) + 1
    store = {
        "long_df": merged,
        "time_col": time_col,
        "flow_col": flow_col,
        "value_cols": value_cols,
        "mapping": mapping,
        "original_columns": original_columns,
        "name": source_name(source_id),
        "last_updated": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "import_count": import_count,
    }
    _save_store(source_id, store)
    return store


def detect_granularity(long_df: pd.DataFrame, time_col: str) -> str:
    """根据时间列判断数据粒度：含非零时分秒 → 小时级，否则天级。"""
    if time_col is None or time_col not in long_df.columns:
        return "daily"
    tser = pd.to_datetime(long_df[time_col], errors="coerce").dropna()
    if tser.empty:
        return "daily"
    for ts in tser:
        if ts.hour != 0 or ts.minute != 0 or ts.second != 0:
            return "hourly"
    return "daily"


def _try_clear_analysis(source_id: str) -> None:
    """数据全部清除时，同步删除对应的分析数据文件和字段结构模板。"""
    data_dir = Path(BASE_DIR) / "data"
    for fname in (f"analysis_{source_id}.pkl", f"template_{source_id}.pkl"):
        p = data_dir / fname
        if p.exists():
            try:
                p.unlink()
            except OSError:
                pass


def clear_data(source_id: str, start_date: str = "", end_date: str = "") -> Dict[str, Any]:
    """清除指定数据源中给定时间段的数据，返回清除行数与剩余行数。"""
    store = load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return {"cleared": 0, "remaining": 0, "has_data": False}
    df = store["long_df"]
    time_col = store.get("time_col")
    original_count = len(df)
    if time_col and time_col in df.columns:
        tser = pd.to_datetime(df[time_col], errors="coerce")
        mask = pd.Series(True, index=df.index)
        if start_date:
            t0 = pd.to_datetime(start_date, errors="coerce")
            if pd.notna(t0):
                mask &= (tser >= t0)
        if end_date:
            t1 = pd.to_datetime(end_date, errors="coerce")
            if pd.notna(t1):
                t1_end = t1 + pd.Timedelta(days=1) - pd.Timedelta(seconds=1)
                mask &= (tser <= t1_end)
        df = df[~mask].reset_index(drop=True)
    else:
        df = df.iloc[0:0].reset_index(drop=True)
    cleared = original_count - len(df)
    store["long_df"] = df
    store["last_updated"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    _save_store(source_id, store)
    # 数据全部清除时，同步删除对应的分析数据
    if df.empty:
        _try_clear_analysis(source_id)
    return {"cleared": cleared, "remaining": len(df), "has_data": not df.empty}


def get_summary(source_id: str) -> Dict[str, Any]:
    store = load_store(source_id)
    name = source_name(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return {
            "source_id": source_id, "name": name, "source_name": name, "has_data": False,
            "row_count": 0, "long_row_count": 0,
            "time_min": None, "time_max": None,
            "last_updated": None, "import_count": 0,
        }
    df = store["long_df"]
    time_col = store.get("time_col")
    time_min = time_max = None
    row_count = 0
    if time_col and time_col in df.columns:
        tser = pd.to_datetime(df[time_col], errors="coerce")
        if tser.notna().any():
            has_time = any((t.hour != 0 or t.minute != 0) for t in tser.dropna())
            fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"
            time_min = tser.min().strftime(fmt)
            time_max = tser.max().strftime(fmt)
        row_count = int(df[time_col].nunique(dropna=True))
    return {
        "source_id": source_id, "name": name, "source_name": name, "has_data": True,
        "row_count": row_count, "long_row_count": int(len(df)),
        "time_min": time_min, "time_max": time_max,
        "last_updated": store.get("last_updated"),
        "import_count": int(store.get("import_count", 0)),
    }


def get_module_summary(module_id: str) -> Dict[str, Any]:
    """返回一个模块下天级 + 小时级的摘要。"""
    name = module_name(module_id)
    sources: Dict[str, Any] = {}
    for g in GRANULARITIES:
        sid = granularity_source_id(module_id, g["id"])
        sources[g["id"]] = get_summary(sid)
    return {"module_id": module_id, "name": name, "sources": sources}


def build_wide_preview(source_id: str, max_rows: int = 200) -> Dict[str, Any]:
    """从存储的长表重新透视生成宽表预览。"""
    from .validator import _pivot_to_wide, _wide_rows
    summary = get_summary(source_id)
    store = load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return {**summary, "time_col": "",
                "wide_columns": [], "wide_header_rows": [], "wide_preview_rows": [],
                "errors": []}
    df = store["long_df"]
    time_col = store.get("time_col") or ""
    flow_col = store.get("flow_col")
    value_cols = store.get("value_cols")
    try:
        wide_df, flat_cols, header_rows = _pivot_to_wide(df, time_col, flow_col, value_cols)
        all_rows = _wide_rows(wide_df)
        rows = all_rows[:max_rows] if max_rows and max_rows > 0 else all_rows
        return {**summary, "time_col": time_col,
                "wide_columns": flat_cols, "wide_header_rows": header_rows,
                "wide_preview_rows": rows, "row_count": int(len(wide_df)),
                "preview_row_count": int(len(rows)),
                "errors": []}
    except Exception as e:
        return {**summary, "time_col": time_col,
                "wide_columns": [], "wide_header_rows": [], "wide_preview_rows": [],
                "errors": [f"宽表转换失败: {e}"]}



# ---------------------------------------------------------------------------
# 字段结构模板：每个数据源(module×granularity)首次导入时确认的字段结构，
# 后续导入据此比对；结构一致则直接导入，不一致则提示手动确认。
# ---------------------------------------------------------------------------
def _template_path(source_id: str) -> Path:
    return DATA_DIR / f"template_{source_id}.pkl"


def load_template(source_id: str) -> Optional[Dict[str, Any]]:
    p = _template_path(source_id)
    if not p.exists():
        return None
    try:
        with open(p, "rb") as f:
            data = pickle.load(f)
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def save_template(source_id: str, template: Dict[str, Any]) -> None:
    template = dict(template)
    template["source_id"] = source_id
    template["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with open(_template_path(source_id), "wb") as f:
        pickle.dump(template, f)


def compare_field_structures(template_fields: List[str],
                             incoming_fields: List[str]) -> Dict[str, Any]:
    """比对两份字段清单（均为按顺序的字段名列表），返回差异。

    match 为 True 当且仅当：无缺失、无新增、顺序一致。
    """
    t_list = list(template_fields)
    i_list = list(incoming_fields)
    missing = [c for c in t_list if c not in i_list]
    extra = [c for c in i_list if c not in t_list]
    reordered = (not missing and not extra and t_list != i_list)
    match = (not missing and not extra and not reordered)
    return {
        "match": match,
        "missing": missing,
        "extra": extra,
        "reordered": reordered,
        "template_fields": t_list,
        "incoming_fields": i_list,
    }
