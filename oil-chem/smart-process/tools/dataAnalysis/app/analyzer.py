"""宽表物料平衡数据解析与总收率分析。

读取多级表头宽表 Excel（如 2#加裂物料平衡-天级行列转换 格式），
通用识别进/出方向行与数值列，计算每日总收率 = 出总 / 进总 × 100%。
不硬编码字段位置，适应表头行数与列名变化。
"""
from __future__ import annotations

import pickle
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)

YIELD_MIN = 98.0
YIELD_MAX = 102.0

ANALYSIS_SOURCES: List[Dict[str, str]] = []


def _refresh_analysis_sources():
    """从 data_store 动态构建分析数据源列表，排除罐表类模块。"""
    global ANALYSIS_SOURCES
    try:
        from . import data_store
        data_store._refresh_module_globals()
        ANALYSIS_SOURCES = []
        for s in data_store.SOURCES:
            mid = s.get("module_id", "")
            mname = data_store.module_name(mid)
            if "罐表" in mname:
                continue
            ANALYSIS_SOURCES.append({"id": s["id"], "name": s["name"]})
    except Exception:
        ANALYSIS_SOURCES = [
            {"id": "2_hydrocracking", "name": "2#加氢裂化（天级）"},
            {"id": "2_hydrocracking_hourly", "name": "2#加氢裂化（小时级）"},
            {"id": "dcc", "name": "DCC催化裂解"},
        ]


_refresh_analysis_sources()


def analysis_source_ids() -> List[str]:
    _refresh_analysis_sources()
    return [s["id"] for s in ANALYSIS_SOURCES]


def analysis_source_name(source_id: str) -> str:
    _refresh_analysis_sources()
    for s in ANALYSIS_SOURCES:
        if s["id"] == source_id:
            return s["name"]
    return source_id


def _analysis_path(source_id: str) -> Path:
    return DATA_DIR / f"analysis_{source_id}.pkl"


def load_analysis(source_id: str) -> Optional[Dict[str, Any]]:
    p = _analysis_path(source_id)
    if not p.exists():
        return None
    try:
        with open(p, "rb") as f:
            data = pickle.load(f)
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def _save_analysis(source_id: str, data: Dict[str, Any]) -> None:
    with open(_analysis_path(source_id), "wb") as f:
        pickle.dump(data, f)


def _find_direction_row(raw: pd.DataFrame, max_scan: int = 20) -> Optional[int]:
    for i in range(min(len(raw), max_scan)):
        vals = [str(v) for v in raw.iloc[i, :].values]
        if "进" in vals and "出" in vals:
            return i
    return None


def _find_data_start(raw: pd.DataFrame, max_scan: int = 30) -> Optional[int]:
    for i in range(min(len(raw), max_scan)):
        ts = pd.to_datetime(raw.iloc[i, 0], errors="coerce")
        if pd.notna(ts):
            return i
    return None


def parse_wide_balance_excel(path: str) -> Dict[str, Any]:
    errors: List[str] = []
    adjustments: List[str] = []
    try:
        raw = pd.read_excel(path, sheet_name=0, header=None)
    except Exception as e:
        return {"points": [], "header_info": {}, "errors": [f"读取 Excel 失败: {e}"], "adjustments": []}

    if raw.empty or raw.shape[1] < 2:
        return {"points": [], "header_info": {}, "errors": ["Excel 为空或列数不足"], "adjustments": []}

    data_start = _find_data_start(raw)
    if data_start is None:
        return {"points": [], "header_info": {}, "errors": ["未找到日期数据行，无法识别数据起始位置"], "adjustments": []}
    colname_row = data_start - 1
    colnames = [str(v) for v in raw.iloc[colname_row, :].values]

    direction_row = _find_direction_row(raw.iloc[: max(colname_row + 1, 1), :])
    if direction_row is None:
        errors.append("未找到进出方向行（含进与出），无法区分进出物料")
        return {"points": [], "header_info": {}, "errors": errors, "adjustments": adjustments}
    directions = [str(v) for v in raw.iloc[direction_row, :].values]

    val_cols: List[int] = []
    for i, c in enumerate(colnames):
        if c in ("bal_cfm_value", "bal_cfm", "value", "数值", "数量"):
            val_cols.append(i)
    if not val_cols:
        for i in range(len(colnames)):
            if directions[i] in ("进", "出"):
                col_data = pd.to_numeric(raw.iloc[data_start:, i], errors="coerce")
                if col_data.notna().sum() >= max(len(col_data) * 0.5, 1):
                    val_cols.append(i)
        if val_cols:
            adjustments.append("未找到 bal_cfm_value 标识列，按数值类型自动识别数值列")
    if not val_cols:
        errors.append("未找到数值列，无法计算总收率")
        return {"points": [], "header_info": {}, "errors": errors, "adjustments": adjustments}

    jin_cols = [i for i in val_cols if directions[i] == "进"]
    chu_cols = [i for i in val_cols if directions[i] == "出"]
    if not jin_cols:
        errors.append("未找到进方向数值列")
        return {"points": [], "header_info": {}, "errors": errors, "adjustments": adjustments}
    if not chu_cols:
        errors.append("未找到出方向数值列")
        return {"points": [], "header_info": {}, "errors": errors, "adjustments": adjustments}

    data = raw.iloc[data_start:, :].copy().reset_index(drop=True)
    dates = pd.to_datetime(data.iloc[:, 0], errors="coerce")
    jin_total = pd.to_numeric(data.iloc[:, jin_cols].sum(axis=1), errors="coerce")
    chu_total = pd.to_numeric(data.iloc[:, chu_cols].sum(axis=1), errors="coerce")
    yield_pct = (chu_total / jin_total * 100.0)

    valid = dates.notna() & jin_total.notna() & (jin_total != 0) & yield_pct.notna()
    dates = dates[valid].reset_index(drop=True)
    jin_total = jin_total[valid].reset_index(drop=True)
    chu_total = chu_total[valid].reset_index(drop=True)
    yield_pct = yield_pct[valid].reset_index(drop=True)

    if len(dates) == 0:
        errors.append("无有效数据行（日期或进总为空）")
        return {"points": [], "header_info": {}, "errors": errors, "adjustments": adjustments}

    has_time = any((d.hour != 0 or d.minute != 0 or d.second != 0) for d in dates)
    date_fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"
    points: List[Dict[str, Any]] = []
    for i in range(len(dates)):
        yv = round(float(yield_pct.iloc[i]), 4)
        points.append({
            "date": dates.iloc[i].strftime(date_fmt),
            "jin_total": round(float(jin_total.iloc[i]), 4),
            "chu_total": round(float(chu_total.iloc[i]), 4),
            "yield": yv,
            "in_range": YIELD_MIN <= yv <= YIELD_MAX,
        })

    header_info = {
        "direction_row": int(direction_row),
        "colname_row": int(colname_row),
        "data_start": int(data_start),
        "n_val_cols": len(val_cols),
        "n_jin_cols": len(jin_cols),
        "n_chu_cols": len(chu_cols),
        "colnames": colnames,
    }
    adjustments.append(f"识别方向行第 {direction_row + 1} 行、列名行第 {colname_row + 1} 行、数据起始第 {data_start + 1} 行")
    adjustments.append(f"数值列 {len(val_cols)} 个（进 {len(jin_cols)}、出 {len(chu_cols)}），数据点 {len(points)} 个")

    return {"points": points, "header_info": header_info, "errors": errors, "adjustments": adjustments}


def _compute_stats(points: List[Dict[str, Any]]) -> Dict[str, Any]:
    if not points:
        return {"count": 0, "date_min": None, "date_max": None, "mean": None,
                "min": None, "max": None, "in_range_count": 0, "above_count": 0,
                "below_count": 0, "in_range_rate": None}
    yields = [p["yield"] for p in points]
    in_range = sum(1 for p in points if p["in_range"])
    above = sum(1 for y in yields if y > YIELD_MAX)
    below = sum(1 for y in yields if y < YIELD_MIN)
    return {
        "count": len(points),
        "date_min": points[0]["date"],
        "date_max": points[-1]["date"],
        "mean": round(sum(yields) / len(yields), 4),
        "min": round(min(yields), 4),
        "max": round(max(yields), 4),
        "in_range_count": in_range,
        "above_count": above,
        "below_count": below,
        "in_range_rate": round(in_range / len(points) * 100, 2),
    }


def save_analysis(source_id: str, parsed: Dict[str, Any], filename: str) -> Dict[str, Any]:
    points = parsed.get("points", [])
    data = {
        "source_id": source_id,
        "source_name": analysis_source_name(source_id),
        "points": points,
        "stats": _compute_stats(points),
        "header_info": parsed.get("header_info", {}),
        "adjustments": parsed.get("adjustments", []),
        "filename": filename,
        "imported_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "yield_min": YIELD_MIN,
        "yield_max": YIELD_MAX,
    }
    _save_analysis(source_id, data)
    return data


def compute_yield_from_store(store: Dict[str, Any], neg_filter: str = "filter", unit: str = "") -> Dict[str, Any]:
    """从导入存储的长表计算总收率趋势（出总/进总×100%）。

    直接使用导入页面的数据，保证分析与导入数据完全一致。
    """
    errors: List[str] = []
    adjustments: List[str] = []
    df = store.get("long_df")
    if df is None or (hasattr(df, "empty") and df.empty):
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["该数据源暂无导入数据，请先在导入页面导入数据"], "adjustments": []}

    time_col = store.get("time_col")
    flow_col = store.get("flow_col")
    value_cols = store.get("value_cols") or []
    if not time_col or time_col not in df.columns:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["未识别到时间列，无法计算总收率"], "adjustments": []}

    # 识别进出类型列（取值仅为 进/出）
    io_col_name: Optional[str] = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"进", "出"}:
            io_col_name = c
            break
    if io_col_name is None:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["未识别到进出类型列（含进与出），无法计算总收率"], "adjustments": []}

    # 数值列：与宽表汇总一致——优先选名称含 bal 的数值列，无则用第一个
    summary_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            summary_col = vc
            break
    if summary_col is None:
        candidate = flow_col if (flow_col and flow_col in value_cols) else (value_cols[0] if value_cols else flow_col)
        summary_col = candidate if (candidate and candidate in df.columns) else None
    if summary_col is None:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["未识别到数值列，无法计算总收率"], "adjustments": []}

    # 识别密度列（气体密度/密度/density）：气体物料需用 数量×密度/1000 换算为质量(吨)，
    # 液体密度=1000 时 ×1000/1000=原值不变。无密度列则直接用数值列。
    density_col: Optional[str] = None
    for c in df.columns:
        if c == time_col or c == summary_col:
            continue
        cn = str(c).strip().lower()
        if "密度" in str(c) or "density" in cn:
            density_col = c
            break

    # 识别装置列
    unit_col = None
    for c2 in df.columns:
        if c2 == time_col or c2 == io_col_name or c2 == summary_col:
            continue
        cn2 = str(c2).strip().lower()
        if "unit" in cn2 or "装置" in str(c2) or "装置编号" in str(c2):
            unit_col = c2
            break
    if unit and unit_col:
        adjustments.append(f"按装置筛选: {unit}")

    cols_needed = [time_col, io_col_name, summary_col]
    if density_col and density_col not in cols_needed:
        cols_needed.append(density_col)
    if unit_col:
        cols_needed.append(unit_col)
    work = df[cols_needed].copy()
    if unit and unit_col:
        work = work[work[unit_col].astype(str) == unit]
    work[time_col] = pd.to_datetime(work[time_col], errors="coerce")
    work[summary_col] = pd.to_numeric(work[summary_col], errors="coerce")
    work = work.dropna(subset=[time_col, summary_col])
    if neg_filter == "filter":
        work = work[work[summary_col] >= 0]
    if work.empty:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["无有效数据行"], "adjustments": []}

    # 计算有效质量值
    value_col = "_effective_value"
    if density_col and density_col in work.columns:
        dens = pd.to_numeric(work[density_col], errors="coerce")
        qty = work[summary_col]
        effective = qty.copy()
        mask = dens.notna() & (dens != 0)
        effective[mask] = qty[mask] * dens[mask] / 1000.0
        work[value_col] = effective
        adjustments.append(f"检测到密度列「{density_col}」，已按 数量×密度/1000 换算为质量(吨)")
    else:
        work[value_col] = work[summary_col]

    grouped = work.groupby([time_col, io_col_name])[value_col].sum()
    pivot = grouped.unstack(io_col_name)
    if "进" not in pivot.columns or "出" not in pivot.columns:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["缺少进或出方向数据，无法计算总收率"], "adjustments": []}

    jin_total = pivot["进"]
    chu_total = pivot["出"]
    yield_pct = (chu_total / jin_total.replace(0, pd.NA) * 100.0)

    valid = jin_total.notna() & chu_total.notna() & (jin_total != 0) & yield_pct.notna()
    dates = jin_total.index[valid]
    jin_total = jin_total[valid]
    chu_total = chu_total[valid]
    yield_pct = yield_pct[valid]

    if len(dates) == 0:
        return {"points": [], "stats": {}, "header_info": {},
                "errors": ["无有效数据行（进总为空或为零）"], "adjustments": []}

    has_time = any((d.hour != 0 or d.minute != 0 or d.second != 0) for d in dates)
    date_fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"
    points: List[Dict[str, Any]] = []
    for i in range(len(dates)):
        yv = round(float(yield_pct.iloc[i]), 4)
        points.append({
            "date": dates[i].strftime(date_fmt),
            "jin_total": round(float(jin_total.iloc[i]), 4),
            "chu_total": round(float(chu_total.iloc[i]), 4),
            "yield": yv,
            "in_range": YIELD_MIN <= yv <= YIELD_MAX,
        })

    total_times = int(pd.to_datetime(df[time_col], errors="coerce").nunique())
    dropped = total_times - len(points)
    adjustments.append(f"基于导入数据计算：进出类型列「{io_col_name}」，数值列「{summary_col}」")
    adjustments.append(f"时间点 {total_times} 个，有效收率点 {len(points)} 个" +
                       (f"（已排除进总为零/为空 {dropped} 个）" if dropped else ""))

    return {
        "points": points,
        "stats": _compute_stats(points),
        "header_info": {"io_col": io_col_name, "value_col": summary_col,
                        "total_times": total_times, "n_points": len(points)},
        "errors": errors,
        "adjustments": adjustments,
    }


def get_yield_trend(source_id: str) -> Dict[str, Any]:
    data = load_analysis(source_id)
    if not data:
        return {
            "source_id": source_id,
            "source_name": analysis_source_name(source_id),
            "has_data": False, "points": [], "stats": {}, "errors": ["该数据源暂无分析数据，请先导入"],
            "yield_min": YIELD_MIN, "yield_max": YIELD_MAX,
        }
    data["has_data"] = True
    data["errors"] = []
    return data


def _identify_material_name_col(df, time_col, io_col, value_cols):
    """\u901a\u7528\u8bc6\u522b\u7269\u6599\u540d\u79f0\u5217\u3002"""
    exclude = {time_col, io_col} | set(value_cols or [])
    candidates = []
    for c in df.columns:
        if c in exclude:
            continue
        s = str(c).strip().lower()
        vals = df[c].dropna().astype(str)
        if vals.empty:
            continue
        nunique = vals.nunique()
        # \u6392\u9664\u4ee3\u7801/ID\u5217\uff08\u51e0\u4e4e\u5168\u552f\u4e00\u6216\u540d\u79f0\u542b code/id\uff09
        if nunique >= len(vals) * 0.9 or "code" in s or "_id" in s or s.endswith("id"):
            continue
        # \u6392\u9664\u6570\u503c\u5217
        try:
            num_ratio = pd.to_numeric(vals, errors="coerce").notna().sum() / len(vals)
            if num_ratio > 0.8:
                continue
        except Exception:
            pass
        priority = 0
        if "display" in s:
            priority = 100
        elif "\u540d\u79f0" in str(c) or "\u7269\u6599" in str(c):
            priority = 90
        elif "alias" in s or "\u4fa7\u7ebf" in str(c):
            priority = 80
        candidates.append((priority, c, nunique))
    if not candidates:
        return None
    candidates.sort(key=lambda x: (-x[0], x[2]))
    return candidates[0][1]


def compute_material_distribution_from_store(store: Dict[str, Any], neg_filter: str = "filter",
                                             start_date: str = "", end_date: str = "",
                                             exclude_dates: str = "", unit: str = "") -> Dict[str, Any]:
    """计算进/出方向各物料的占比（总量占比），用于饼图展示。"""
    df = store.get("long_df")
    if df is None or (hasattr(df, "empty") and df.empty):
        return {"has_data": False, "errors": ["该数据源暂无导入数据"]}

    time_col = store.get("time_col")
    flow_col = store.get("flow_col")
    value_cols = store.get("value_cols") or []
    if not time_col or time_col not in df.columns:
        return {"has_data": False, "errors": ["未识别到时间列"]}

    # 日期筛选
    df = df.copy()
    df[time_col] = pd.to_datetime(df[time_col], errors="coerce")
    if start_date:
        df = df[df[time_col] >= pd.Timestamp(start_date)]
    if end_date:
        df = df[df[time_col] <= pd.Timestamp(end_date) + pd.Timedelta(days=1)]
    if exclude_dates:
        exc = [d.strip() for d in exclude_dates.split(",") if d.strip()]
        if exc:
            df = df[~df[time_col].dt.strftime("%Y-%m-%d").isin(exc)]

    # 识别装置列并按装置筛选
    unit_col = None
    for c2 in df.columns:
        if c2 == time_col:
            continue
        cn2 = str(c2).strip().lower()
        if "unit" in cn2 or "装置" in str(c2) or "装置编号" in str(c2):
            unit_col = c2
            break
    if unit and unit_col:
        df = df[df[unit_col].astype(str) == unit]

    # 识别进出类型列
    io_col_name = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"进", "出"}:
            io_col_name = c
            break
    if io_col_name is None:
        return {"has_data": False, "errors": ["未识别到进出类型列"]}

    # 数值列
    summary_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            summary_col = vc
            break
    if summary_col is None:
        candidate = flow_col if (flow_col and flow_col in value_cols) else (value_cols[0] if value_cols else flow_col)
        summary_col = candidate if (candidate and candidate in df.columns) else None
    if summary_col is None:
        return {"has_data": False, "errors": ["未识别到数值列"]}

    # 密度列
    density_col = None
    for c in df.columns:
        if c == time_col or c == summary_col:
            continue
        cn = str(c).strip().lower()
        if "密度" in str(c) or "density" in cn:
            density_col = c
            break

    work = df[[time_col, io_col_name, summary_col]].copy()
    if density_col:
        work[density_col] = df[density_col]
    work[summary_col] = pd.to_numeric(work[summary_col], errors="coerce")
    work = work.dropna(subset=[time_col, summary_col])
    if neg_filter == "filter":
        work = work[work[summary_col] >= 0]
    if work.empty:
        return {"has_data": False, "errors": ["无有效数据行"]}

    # 计算有效质量值
    value_col = "_effective_value"
    if density_col and density_col in work.columns:
        dens = pd.to_numeric(work[density_col], errors="coerce")
        qty = work[summary_col]
        effective = qty.copy()
        mask = dens.notna() & (dens != 0)
        effective[mask] = qty[mask] * dens[mask] / 1000.0
        work[value_col] = effective
    else:
        work[value_col] = work[summary_col]

    # 识别物料名称列
    name_col = _identify_material_name_col(df, time_col, io_col_name, [summary_col])
    if name_col is None:
        return {"has_data": False, "errors": ["未识别到物料名称列"]}
    work[name_col] = df.loc[work.index, name_col].values

    # 按进出方向和物料分组求和
    result = {"has_data": True, "in_materials": [], "out_materials": [], "errors": []}
    for direction, key in [("进", "in_materials"), ("出", "out_materials")]:
        sub = work[work[io_col_name] == direction]
        if sub.empty:
            result[key] = []
            continue
        grouped = sub.groupby(name_col)[value_col].sum().sort_values(ascending=False)
        total = grouped.sum()
        materials = []
        for mat, val in grouped.items():
            if total > 0:
                pct = round(float(val) / float(total) * 100, 2)
            else:
                pct = 0
            materials.append({"name": str(mat), "value": round(float(val), 2), "percent": pct})
        result[key] = materials

    return result


def compute_material_trend_from_store(store: Dict[str, Any], neg_filter: str = "filter") -> Dict[str, Any]:
    """\u4ece\u5bfc\u5165\u6570\u636e\u8ba1\u7b97\u5404\u7269\u6599\u7684\u65f6\u95f4\u5e8f\u5217\u8d8b\u52bf\u3002"""
    df = store.get("long_df")
    if df is None or (hasattr(df, "empty") and df.empty):
        return {"has_data": False, "materials": [], "dates": [], "series": {}, "errors": ["\u8be5\u6570\u636e\u6e90\u6682\u65e0\u5bfc\u5165\u6570\u636e"]}

    time_col = store.get("time_col")
    flow_col = store.get("flow_col")
    value_cols = store.get("value_cols") or []
    if not time_col or time_col not in df.columns:
        return {"has_data": False, "materials": [], "dates": [], "series": {}, "errors": ["\u672a\u8bc6\u522b\u5230\u65f6\u95f4\u5217"]}

    # \u8bc6\u522b\u8fdb\u51fa\u7c7b\u578b\u5217
    io_col_name: Optional[str] = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"\u8fdb", "\u51fa"}:
            io_col_name = c
            break

    # \u8bc6\u522b\u6570\u503c\u5217\uff08\u4e0e\u6536\u7387\u8ba1\u7b97\u4e00\u81f4\uff09
    summary_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            summary_col = vc
            break
    if summary_col is None:
        candidate = flow_col if (flow_col and flow_col in value_cols) else (value_cols[0] if value_cols else None)
        summary_col = candidate if (candidate and candidate in df.columns) else None
    if summary_col is None:
        return {"has_data": False, "materials": [], "dates": [], "series": {}, "errors": ["\u672a\u8bc6\u522b\u5230\u6570\u503c\u5217"]}

    # \u8bc6\u522b\u7269\u6599\u540d\u79f0\u5217
    name_col = _identify_material_name_col(df, time_col, io_col_name, value_cols)
    if name_col is None:
        return {"has_data": False, "materials": [], "dates": [], "series": {}, "errors": ["\u672a\u8bc6\u522b\u5230\u7269\u6599\u540d\u79f0\u5217"]}

    work = df[[time_col, name_col, summary_col]].copy() if (io_col_name is None or io_col_name not in df.columns) else df[[time_col, name_col, io_col_name, summary_col]].copy()
    work[time_col] = pd.to_datetime(work[time_col], errors="coerce")
    work[summary_col] = pd.to_numeric(work[summary_col], errors="coerce")
    work = work.dropna(subset=[time_col, summary_col, name_col])
    if neg_filter == "filter":
        work = work[work[summary_col] >= 0]
    if work.empty:
        return {"has_data": False, "materials": [], "dates": [], "series": {}, "errors": ["\u65e0\u6709\u6548\u6570\u636e\u884c"]}

    has_time = any((d.hour != 0 or d.minute != 0) for d in work[time_col])
    date_fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"
    all_dates = sorted(work[time_col].unique())
    date_strs = [d.strftime(date_fmt) for d in all_dates]

    # \u6309\u65f6\u95f4 + \u7269\u6599\u540d\u79f0\u5206\u7ec4\u6c42\u548c
    if io_col_name and io_col_name in work.columns:
        grouped = work.groupby([time_col, name_col, io_col_name])[summary_col].sum().reset_index()
        materials_info = []
        for name in sorted(work[name_col].astype(str).unique()):
            sub = grouped[grouped[name_col] == name]
            dirs = sorted(sub[io_col_name].astype(str).unique())
            for d in dirs:
                materials_info.append({"name": str(name), "direction": d})
    else:
        grouped = work.groupby([time_col, name_col])[summary_col].sum().reset_index()
        materials_info = [{"name": str(n), "direction": ""} for n in sorted(work[name_col].astype(str).unique())]

    # \u6784\u5efa series: { material_key: { date_str: value } }
    series = {}
    for mi in materials_info:
        key = mi["name"] + ("\u00b7" + mi["direction"] if mi["direction"] else "")
        if io_col_name and io_col_name in grouped.columns:
            sub = grouped[(grouped[name_col] == mi["name"]) & (grouped[io_col_name] == mi["direction"])]
        else:
            sub = grouped[grouped[name_col] == mi["name"]]
        date_val = {}
        for _, row in sub.iterrows():
            ds = row[time_col].strftime(date_fmt)
            date_val[ds] = round(float(row[summary_col]), 4)
        series[key] = date_val

    return {
        "has_data": True,
        "materials": materials_info,
        "dates": date_strs,
        "series": series,
        "name_col": name_col,
        "value_col": summary_col,
        "errors": [],
    }



def compute_io_flow_from_store(store: Dict[str, Any], neg_filter: str = "filter") -> Dict[str, Any]:
    """计算每天进/出物料总量，支持按装置筛选。

    返回每个时间点的进料总量和出料总量，
    以及可用装置列表（用于下拉菜单选择）。
    """
    df = store.get("long_df")
    if df is None or (hasattr(df, "empty") and df.empty):
        return {"has_data": False, "dates": [], "series": {}, "units": [], "errors": ["该数据源暂无导入数据"]}

    time_col = store.get("time_col")
    flow_col = store.get("flow_col")
    value_cols = store.get("value_cols") or []
    if not time_col or time_col not in df.columns:
        return {"has_data": False, "dates": [], "series": {}, "units": [], "errors": ["未识别到时间列"]}

    # 识别进出类型列
    io_col_name = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"进", "出"}:
            io_col_name = c
            break
    if io_col_name is None:
        return {"has_data": False, "dates": [], "series": {}, "units": [], "errors": ["未识别到进出类型列"]}

    # 识别数值列
    summary_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            summary_col = vc
            break
    if summary_col is None:
        candidate = flow_col if (flow_col and flow_col in value_cols) else (value_cols[0] if value_cols else None)
        summary_col = candidate if (candidate and candidate in df.columns) else None
    if summary_col is None:
        return {"has_data": False, "dates": [], "series": {}, "units": [], "errors": ["未识别到数值列"]}

    # 识别装置列（列名含装置/unit，或第一列）
    unit_col = None
    for c in df.columns:
        if c == time_col or c == io_col_name or c == summary_col:
            continue
        cn = str(c).strip().lower()
        if "unit" in cn or "装置" in str(c) or "装置编号" in str(c):
            unit_col = c
            break

    work = df[[time_col, io_col_name, summary_col]].copy()
    if unit_col:
        work = df[[time_col, unit_col, io_col_name, summary_col]].copy()
    work[time_col] = pd.to_datetime(work[time_col], errors="coerce")
    work[summary_col] = pd.to_numeric(work[summary_col], errors="coerce")
    work = work.dropna(subset=[time_col, summary_col])
    if work.empty:
        return {"has_data": False, "dates": [], "series": {}, "units": [], "errors": ["无有效数据行"]}

    if neg_filter == "filter":
        work = work[work[summary_col] >= 0]

    has_time = any((d.hour != 0 or d.minute != 0) for d in work[time_col])
    date_fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"

    # 获取所有装置列表
    units = []
    if unit_col:
        units = sorted(work[unit_col].dropna().astype(str).unique().tolist())

    all_dates = sorted(work[time_col].unique())
    date_strs = [d.strftime(date_fmt) for d in all_dates]

    # 构建 series: { key: { date_str: value } }
    # key = "total_jin" / "total_chu" / "unit:XXX_jin" / "unit:XXX_chu"
    series = {}

    # 总进出
    total_jin = {}
    total_chu = {}
    jin_work = work[work[io_col_name] == "进"]
    chu_work = work[work[io_col_name] == "出"]
    for d in all_dates:
        ds = d.strftime(date_fmt)
        jv = jin_work[jin_work[time_col] == d][summary_col].sum()
        cv = chu_work[chu_work[time_col] == d][summary_col].sum()
        if jv != 0:
            total_jin[ds] = round(float(jv), 4)
        if cv != 0:
            total_chu[ds] = round(float(cv), 4)
    series["总进料"] = total_jin
    series["总出料"] = total_chu

    # 各装置进出
    if unit_col:
        for u in units:
            u_jin = {}
            u_chu = {}
            u_work = work[work[unit_col] == u]
            u_jin_work = u_work[u_work[io_col_name] == "进"]
            u_chu_work = u_work[u_work[io_col_name] == "出"]
            for d in all_dates:
                ds = d.strftime(date_fmt)
                jv = u_jin_work[u_jin_work[time_col] == d][summary_col].sum()
                cv = u_chu_work[u_chu_work[time_col] == d][summary_col].sum()
                if jv != 0:
                    u_jin[ds] = round(float(jv), 4)
                if cv != 0:
                    u_chu[ds] = round(float(cv), 4)
            series[u + " 进"] = u_jin
            series[u + " 出"] = u_chu

    return {
        "has_data": True,
        "dates": date_strs,
        "series": series,
        "units": units,
        "unit_col": unit_col,
        "errors": [],
    }


# ---------------------------------------------------------------------------
# 罐表分析：按罐号、按油品（原料）统计库存变化趋势
# ---------------------------------------------------------------------------

def is_tank_data(store: dict) -> bool:
    """判断存储的数据是否为罐表数据（含 罐号 列）。"""
    if not store or not store.get("long_df") is not None:
        df = store.get("long_df") if store else None
        if df is None or df.empty:
            return False
        return any("罐" in str(c) and "号" in str(c) for c in df.columns)
    return False


def tank_data_sources() -> list:
    """返回所有含罐表数据的数据源列表。"""
    from . import data_store
    result = []
    for sid in data_store.source_ids():
        store = data_store.load_store(sid)
        if store and store.get("long_df") is not None and not store["long_df"].empty:
            df = store["long_df"]
            has_tank = any("罐" in str(c) and "号" in str(c) for c in df.columns)
            if has_tank:
                mid = data_store.source_module_id(sid)
                mname = data_store.module_name(mid)
                gran = data_store.source_granularity(sid)
                gran_label = "天级" if gran == "daily" else "小时级"
                result.append({
                    "source_id": sid,
                    "name": f"{mname}（{gran_label}）",
                    "row_count": len(df),
                })
    return result


def _find_tank_col(df) -> str:
    """找到罐号列名。"""
    for c in df.columns:
        cs = str(c).strip()
        if cs == "罐号" or cs == "罐  号" or ("罐" in cs and "号" in cs):
            return str(c)
    return ""


def _find_product_col(df) -> str:
    """找到油品名称列名。"""
    for c in df.columns:
        cs = str(c).strip()
        if "油品" in cs or "物料" in cs:
            return str(c)
    return ""


def _find_volume_cols(df) -> list:
    """找到罐量（体积）数值列，返回列名列表。"""
    result = []
    for c in df.columns:
        cs = str(c).strip()
        if "罐量" in cs:
            result.append(str(c))
    # 如果没找到罐量，尝试所有数值列（排除序号、液位）
    if not result:
        for c in df.columns:
            cs = str(c).strip()
            if cs == "序号" or "液位" in cs:
                continue
            if df[c].dtype in ["float64", "int64", "Int64"]:
                result.append(str(c))
    return result


def compute_tank_overview(source_id: str) -> dict:
    """获取罐表数据概览：罐号列表、油品列表、数值列、日期范围。"""
    from . import data_store
    store = data_store.load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return {"has_data": False, "errors": ["该数据源无数据"]}
    df = store["long_df"]
    time_col = store.get("time_col") or ""
    tank_col = _find_tank_col(df)
    product_col = _find_product_col(df)
    volume_cols = _find_volume_cols(df)
    if not tank_col:
        return {"has_data": False, "errors": ["未找到罐号列，非罐表数据"]}

    # 罐号列表（带油品名称列表）
    tanks = []
    tank_products = df.groupby(tank_col)[product_col].apply(
        lambda s: [str(v) for v in s.dropna().unique()]) if product_col else {}
    for tank_id in df[tank_col].dropna().unique():
        prods = tank_products.get(tank_id, []) if product_col else []
        tanks.append({"tank_id": str(tank_id), "products": prods, "product": prods[0] if prods else ""})

    # 油品列表（带罐数和罐号列表）
    materials = []
    if product_col:
        mat_tanks = df.groupby(product_col)[tank_col].apply(
            lambda s: [str(v) for v in s.dropna().unique()])
        for mat in df[product_col].dropna().unique():
            tank_list = mat_tanks.get(mat, [])
            materials.append({"name": str(mat), "tank_count": len(tank_list), "tanks": tank_list})

    # 日期范围
    dates = sorted(df[time_col].dropna().unique()) if time_col in df.columns else []
    date_strs = [pd.Timestamp(d).strftime("%Y-%m-%d") for d in dates]

    return {
        "has_data": True,
        "source_id": source_id,
        "time_col": time_col,
        "tank_col": tank_col,
        "product_col": product_col,
        "volume_cols": volume_cols,
        "tanks": tanks,
        "materials": materials,
        "dates": date_strs,
        "row_count": len(df),
        "errors": [],
    }


def compute_tank_trend(source_id: str, tanks: list = None,
                       materials: list = None, time_point: str = "前尺") -> dict:
    """计算罐/原料库存变化趋势。

    tanks: 选中的罐号列表
    materials: 选中的油品名称列表
    time_point: 时间点 "前尺" / "12点" / "0点"

    选择逻辑：
      - 只选罐号（不选物料）→ 展开为该罐下每种物料的独立线条
      - 只选物料（不选罐号）→ 展开为该物料下每个罐的独立线条
      - 两者都选 → 罐号线（该罐所有物料合计）+ 物料线（该物料所有罐合计）
    """
    from . import data_store
    store = data_store.load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return {"has_data": False, "errors": ["无数据"]}
    df = store["long_df"].copy()
    time_col = store.get("time_col") or ""
    tank_col = _find_tank_col(df)
    product_col = _find_product_col(df)

    if not tank_col or not time_col:
        return {"has_data": False, "errors": ["缺少罐号或时间列"]}

    # 时间点 -> 列名映射
    tp_map = {
        "前尺": ("前尺_液位", "前尺_罐量"),
        "12点": ("12:00:00_液位", "12:00:00_罐量"),
        "0点": ("00:00_液位", "00:00_罐量"),
    }
    if time_point not in tp_map:
        time_point = "前尺"
    level_col, volume_col = tp_map[time_point]

    available_level = level_col if level_col in df.columns else ""
    available_volume = volume_col if volume_col in df.columns else ""
    if not available_level and not available_volume:
        return {"has_data": False, "errors": ["未找到 %s 对应的液位/罐量列" % time_point]}

    if available_level:
        df[available_level] = pd.to_numeric(df[available_level], errors="coerce")
    if available_volume:
        df[volume_col] = pd.to_numeric(df[volume_col], errors="coerce")

    dates = sorted(df[time_col].dropna().unique())
    date_strs = [pd.Timestamp(d).strftime("%Y-%m-%d") for d in dates]

    tanks = tanks or []
    materials = materials or []
    only_tanks = bool(tanks) and not materials
    only_mats = bool(materials) and not tanks
    both = bool(tanks) and bool(materials)

    # 构建线条列表
    items = []
    if both:
        # 同时选了罐号和物料：只显示选中罐 × 选中物料的交集组合
        for tk in tanks:
            for mat in materials:
                sub = df[(df[tank_col] == str(tk))]
                if product_col:
                    sub = sub[sub[product_col] == str(mat)]
                if len(sub) > 0:
                    items.append({"key": "tank:%s|mat:%s" % (tk, mat),
                                  "label": "%s / %s" % (tk, mat),
                                  "tank_id": str(tk), "material": str(mat)})
    elif only_tanks:
        # 只选罐号：展开为该罐涉及的每种物料
        for tk in tanks:
            sub = df[df[tank_col] == str(tk)]
            mat_list = sub[product_col].dropna().unique() if product_col else []
            if len(mat_list) == 0:
                items.append({"key": "tank:%s" % tk, "label": str(tk), "tank_id": str(tk), "material": None})
            else:
                for mat in mat_list:
                    items.append({"key": "tank:%s|mat:%s" % (tk, mat),
                                  "label": "%s / %s" % (tk, mat),
                                  "tank_id": str(tk), "material": str(mat)})
    elif only_mats:
        # 只选物料：展开为该物料涉及的每个罐
        for mat in materials:
            sub = df[df[product_col] == str(mat)] if product_col else pd.DataFrame()
            tank_list = sub[tank_col].dropna().unique()
            if len(tank_list) == 0:
                items.append({"key": "mat:%s" % mat, "label": str(mat), "tank_id": None, "material": str(mat)})
            else:
                for tk in tank_list:
                    items.append({"key": "mat:%s|tank:%s" % (mat, tk),
                                  "label": "%s / %s" % (mat, tk),
                                  "tank_id": str(tk), "material": str(mat)})

    if not items:
        return {"has_data": False, "level_series": {}, "volume_series": {},
                "stats": {}, "dates": date_strs, "items": [], "errors": ["请选择罐号或物料"]}

    level_series = {}
    volume_series = {}
    stats = {}

    for item in items:
        sub = df
        if item["tank_id"] is not None:
            sub = sub[sub[tank_col] == item["tank_id"]]
        if item["material"] is not None and product_col:
            sub = sub[sub[product_col] == item["material"]]

        # 按日期聚合
        agg = sub.groupby(time_col).sum()

        level_vals = {}
        volume_vals = {}
        level_list = []
        volume_list = []
        for d in dates:
            ds = pd.Timestamp(d).strftime("%Y-%m-%d")
            ts = pd.Timestamp(d)
            if ts in agg.index:
                if available_level and available_level in agg.columns:
                    lv = agg.loc[ts, available_level]
                    if pd.notna(lv):
                        level_vals[ds] = round(float(lv), 2)
                        level_list.append(float(lv))
                if available_volume and available_volume in agg.columns:
                    vv = agg.loc[ts, available_volume]
                    if pd.notna(vv):
                        volume_vals[ds] = round(float(vv), 2)
                        volume_list.append(float(vv))

        level_series[item["key"]] = level_vals
        volume_series[item["key"]] = volume_vals
        stats[item["key"]] = {
            "label": item["label"],
            "level": _calc_stats(level_list),
            "volume": _calc_stats(volume_list),
        }

    return {
        "has_data": True,
        "time_point": time_point,
        "level_col": available_level,
        "volume_col": available_volume,
        "dates": date_strs,
        "level_series": level_series,
        "volume_series": volume_series,
        "stats": stats,
        "items": [{"key": it["key"], "label": it["label"]} for it in items],
        "errors": [],
    }


def _calc_stats(vals):
    if not vals:
        return {"max": 0, "min": 0, "mean": 0, "total": 0, "count": 0}
    return {
        "max": round(max(vals), 2),
        "min": round(min(vals), 2),
        "mean": round(sum(vals) / len(vals), 2),
        "total": round(sum(vals), 2),
        "count": len(vals),
    }
