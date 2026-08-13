"""化工炼化工艺物料平衡数据 —— 格式校验与结构调整模块。

核心原则（用户硬性要求）：
  1. 不修改任何原始列名 —— 所有展示与内部处理均沿用原始列名。
  2. 保留所有原始字段 —— 不丢弃序号、位号、来源或去向、气体密度等任何列。
  3. 宽表转换：除时间列外，其余字段全部"横竖转换"，每个时间横向对应各测点的
     进/出数值；侧线名称、进出类型等原始字段名作为多级表头保留。

标准结构约定（用于"识别"，而非"重命名"）：
  第一列须为时间列；需含 进出类型、数量(流量) 等可识别列。
  识别仅用于定位时间轴与数值列，绝不改写列名。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field, asdict
from datetime import datetime, date, timedelta
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd


# ---------------------------------------------------------------------------
# 字段角色定义：aliases 仅用于"识别"原始列承担的角色，绝不用于重命名。
# ---------------------------------------------------------------------------
@dataclass
class FieldSpec:
    key: str            # 内部角色键（不外露）
    label: str          # 展示用的标准字段名说明
    aliases: Tuple[str, ...]
    aliases_cn: Tuple[str, ...] = ()   # 中文可识别列名（用于 schema 展示，均为原始可能列名）
    required: bool = True


# role_desc：用于"列识别结果"表的角色描述，刻意使用"X列"措辞，
# 表明这是该列承担的角色，而非把原始列名改成这个名字。
ROLE_DESC: Dict[str, str] = {
    "time":      "时间列",
    "unit":      "装置列",
    "side_line": "侧线列",
    "io_type":   "进出类型列",
    "material":  "物料列",
    "flow":      "流量数值列",
    "uom":       "单位列",
    "remark":    "备注列",
}

STANDARD_FIELDS: List[FieldSpec] = [
    FieldSpec("time",      "时间",     ("time", "date", "日期", "时间", "datetime", "timestamp", "日期时间", "Unnamed: 8"),
                            aliases_cn=("日期时间", "时间", "日期", "Unnamed: 8")),
    FieldSpec("unit",      "装置名称", ("装置", "装置名称", "unit", "unit_name", "device"),
                            aliases_cn=("装置", "装置名称")),
    FieldSpec("side_line", "侧线名称", ("侧线", "侧线名称", "side_line", "sideline", "stream"),
                            aliases_cn=("侧线", "侧线名称")),
    FieldSpec("io_type",   "进出类型", ("进出类型", "进出", "类型", "io_type", "direction", "in_out", "flow_type"),
                            aliases_cn=("进出类型", "进出", "类型")),
    FieldSpec("material",  "物料名称", ("物料", "物料名称", "原料", "material", "feed", "product"),
                            aliases_cn=("物料", "物料名称", "原料")),
    FieldSpec("flow",      "数量",     ("数量", "流量", "数值", "流量值", "原料量", "amount", "rate", "value", "flow"),
                            aliases_cn=("数量", "流量", "数值", "流量值", "原料量")),
    FieldSpec("uom",       "单位",     ("单位", "计量单位", "unit_of_measure", "uom"),
                            aliases_cn=("单位", "计量单位")),
    FieldSpec("remark",    "备注",     ("备注", "说明", "remark", "comment", "note"),
                            aliases_cn=("备注", "说明"), required=False),
]

IN_KEYWORDS = {"进料", "进", "入", "input", "in", "feed", "进料量", "原料", "原料量"}
OUT_KEYWORDS = {"出料", "出", "出料量", "output", "out", "product", "产品", "产物"}


@dataclass
class ValidationReport:
    is_standard: bool
    needs_adjustment: bool
    column_order_ok: bool
    original_columns: List[str]
    column_mapping: Dict[str, str]          # {原始列名: 角色说明(用原始列名自身)}
    missing_required: List[str]
    adjustments: List[str]
    row_count: int
    preview_columns: List[str]
    preview_rows: List[Dict[str, Any]]
    wide_columns: List[str] = field(default_factory=list)
    wide_header_rows: List[List[Dict[str, Any]]] = field(default_factory=list)
    wide_preview_rows: List[Dict[str, Any]] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    # 导出/时间段筛选所需：时间列名与数据时间范围（ISO 日期字符串）
    time_col: str = ""
    time_min: Optional[str] = None
    time_max: Optional[str] = None


# ---------------------------------------------------------------------------
# 识别工具
# ---------------------------------------------------------------------------
def _norm(s: str) -> str:
    if s is None:
        return ""
    return re.sub(r"[\s_\-]+", "", str(s)).strip().lower()


def _match_field(col_name: str) -> Optional[str]:
    n = _norm(col_name)
    if not n:
        return None
    for spec in STANDARD_FIELDS:
        for alias in spec.aliases:
            if _norm(alias) == n:
                return spec.key
    # 包含匹配（要求 alias 长度>=2，避免单字误匹配）
    if len(n) >= 2:
        best_key: Optional[str] = None
        best_len = 0
        for spec in STANDARD_FIELDS:
            for alias in spec.aliases:
                a = _norm(alias)
                if len(a) >= 2 and (a in n or n in a):
                    if len(a) > best_len:
                        best_key = spec.key
                        best_len = len(a)
        if best_key:
            return best_key
    return None


def _build_column_mapping(columns: List[str], df: Optional[pd.DataFrame] = None) -> Dict[str, str]:
    """返回 {原始列名: 角色key}。

    先按列名 alias 匹配；再基于数据内容做兜底识别：
    - 进出类型列：取值仅含 进/出 的列
    - 数值列：数值型且高基数的列(非时间)
    """
    mapping: Dict[str, str] = {}
    used_keys: set = set()
    for col in columns:
        key = _match_field(col)
        if key and key not in used_keys:
            mapping[str(col)] = key
            used_keys.add(key)

    # 数据驱动兜底：识别进出类型列
    if df is not None and "io_type" not in used_keys:
        for col in columns:
            if col in mapping:
                continue
            try:
                vals = set(df[col].dropna().astype(str).str.strip().unique())
                if vals and vals <= {"进", "出"}:
                    mapping[str(col)] = "io_type"
                    used_keys.add("io_type")
                    break
            except Exception:
                continue

    return mapping


def _looks_like_time(value: Any) -> bool:
    if isinstance(value, (datetime, date)):
        return True
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        s = str(int(value))
        if len(s) == 8 and s[:4].isdigit():
            try:
                datetime(int(s[:4]), int(s[4:6]), int(s[6:8]))
                return True
            except Exception:
                return False
    if isinstance(value, str):
        s = value.strip()
        if not s:
            return False
        if re.match(r"^\d{4}[-/.年]\d{1,2}[-/.月]\d{1,2}", s):
            return True
        if re.match(r"^\d{8}$", s):
            try:
                datetime(int(s[:4]), int(s[4:6]), int(s[6:8]))
                return True
            except Exception:
                pass
        try:
            pd.to_datetime(s)
            return True
        except Exception:
            return False
    return False


def _convert_time_column(series: pd.Series) -> pd.Series:
    """将时间列统一转换为 datetime 类型。

    支持整数 YYYYMMDD（如 20260101）、字符串 YYYYMMDD、常规日期字符串等。
    转换失败时返回原列，不报错。
    """
    if pd.api.types.is_datetime64_any_dtype(series):
        return series

    def _try_parse(v):
        if pd.isna(v):
            return pd.NaT
        if isinstance(v, (datetime, date)):
            return pd.Timestamp(v)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            s = str(int(v))
            if len(s) == 8:
                try:
                    return pd.Timestamp(int(s[:4]), int(s[4:6]), int(s[6:8]))
                except Exception:
                    pass
        if isinstance(v, str):
            s = v.strip()
            if re.match(r"^\d{8}$", s):
                try:
                    return pd.Timestamp(int(s[:4]), int(s[4:6]), int(s[6:8]))
                except Exception:
                    pass
            try:
                return pd.Timestamp(s)
            except Exception:
                pass
        try:
            return pd.Timestamp(v)
        except Exception:
            return pd.NaT

    converted = series.apply(_try_parse)
    if converted.isna().all() and series.notna().any():
        return series
    return converted


def _classify_io(name: str) -> str:
    n = _norm(str(name))
    if any(_norm(k) in n for k in IN_KEYWORDS):
        return "进"
    if any(_norm(k) in n for k in OUT_KEYWORDS):
        return "出"
    return str(name)


# ---------------------------------------------------------------------------
# 宽表检测 / 逆透视（当原始数据本身是宽表时）
# ---------------------------------------------------------------------------
def _detect_wide_table(df: pd.DataFrame, mapping: Dict[str, str]) -> Optional[Tuple[str, List[str], List[str]]]:
    """检测真正的宽表：时间列以外有多个数值列，且缺少描述性字段(如进出类型)。

    只有当数据本身是宽表(每个测点占一列)时才需要逆透视。
    判断依据：存在多列数值型列，且没有明确的进出类型列。
    """
    time_col = None
    for c, k in mapping.items():
        if k == "time":
            time_col = c
            break
    if time_col is None:
        return None
    # 如果已有进出类型列，说明是长表，不需要逆透视
    if "io_type" in mapping.values():
        return None
    # 分类：数值列 vs 描述列
    numeric_cols: List[str] = []
    desc_cols: List[str] = []
    for c in df.columns:
        if c == time_col:
            continue
        cs = str(c).strip()
        if cs.startswith("Unnamed:") or cs == "" or cs == "nan":
            continue
        if pd.api.types.is_numeric_dtype(df[c]) and df[c].notna().sum() > 0:
            numeric_cols.append(c)
        else:
            desc_cols.append(c)
    # 宽表特征：至少2个数值列
    if len(numeric_cols) < 2:
        return None
    return (time_col, desc_cols, numeric_cols)


def _melt_wide_table(df: pd.DataFrame, time_col: str, id_cols: List[str],
                     value_cols: List[str]) -> pd.DataFrame:
    """逆透视宽表 -> 长表，保留原始列名，不硬编码字段名。"""
    all_id_cols = [time_col] + id_cols
    long = df.melt(id_vars=all_id_cols, value_vars=value_cols,
                   var_name="测点", value_name="数值")
    long = long[long["数值"].notna()]
    return long


# ---------------------------------------------------------------------------
# 行 -> dict 序列化（保留原始列名）
# ---------------------------------------------------------------------------
def _rows_to_dicts(df: pd.DataFrame, columns: List[str], n: int = 0) -> List[Dict[str, Any]]:
    head = df.head(n) if n > 0 else df
    out: List[Dict[str, Any]] = []
    for _, row in head.iterrows():
        d: Dict[str, Any] = {}
        for col in columns:
            v = row.get(col)
            if isinstance(v, (datetime, date)):
                d[col] = v.strftime("%Y-%m-%d %H:%M:%S") if isinstance(v, datetime) else v.strftime("%Y-%m-%d")
            elif pd.isna(v):
                d[col] = None
            else:
                d[col] = v
        out.append(d)
    return out


def _val_to_jsonable(v: Any) -> Any:
    # Check NaT/NaN first - NaT is subclass of datetime but can't strftime
    try:
        if pd.isna(v):
            return None
    except (TypeError, ValueError):
        pass
    if isinstance(v, (datetime, date)):
        try:
            return v.strftime("%Y-%m-%d %H:%M:%S") if isinstance(v, datetime) else v.strftime("%Y-%m-%d")
        except (ValueError, AttributeError):
            return None
    # numpy 标量 -> Python 标量（避免 int64 等无法 JSON 序列化）
    if hasattr(v, "item"):
        try:
            v = v.item()
        except Exception:
            pass
    if isinstance(v, bool):
        return v
    if isinstance(v, float):
        return round(v, 6)
    if isinstance(v, int):
        return v
    return v


# ---------------------------------------------------------------------------
# 宽表生成：保留全部原始字段，多级表头使用原始字段名
# ---------------------------------------------------------------------------
def _pick_time_and_flow(df: pd.DataFrame, mapping: Dict[str, str]) -> Tuple[Optional[str], Optional[str]]:
    """识别时间列和数值列。通用逻辑，不依赖固定列名。"""
    time_col = next((c for c, k in mapping.items() if k == "time"), None)
    flow_col = next((c for c, k in mapping.items() if k == "flow"), None)

    # 排除时间列、空表头列、纯序号列后的数值型列
    n_rows = len(df)
    def _is_usable_numeric(c):
        if c == time_col:
            return False
        cs = str(c).strip()
        if cs == "" or cs == "nan":
            return False
        # Exclude sequence number columns from flow/value candidates
        if cs == "序号":
            return False
        if not pd.api.types.is_numeric_dtype(df[c]):
            return False
        if df[c].notna().sum() == 0:
            return False
        return True

    numeric_value_cols = [c for c in df.columns if _is_usable_numeric(c)]
    # Prefer named columns; only use Unnamed columns if no named ones exist
    named_cols = [c for c in numeric_value_cols if not str(c).strip().startswith("Unnamed:")]
    if named_cols:
        numeric_value_cols = named_cols

    if flow_col is None:
        # 只有一个数值列时直接选它
        if len(numeric_value_cols) == 1:
            flow_col = numeric_value_cols[0]
        elif len(numeric_value_cols) > 1:
            # 多个数值列：选高基数(取值多样)的作为流量列，
            # 避免误选低基数的分类编码列或纯序号列
            high_card = [c for c in numeric_value_cols
                         if df[c].nunique(dropna=True) > 10]
            if len(high_card) == 1:
                flow_col = high_card[0]
            elif len(high_card) > 1:
                # 选 max 值最小的(流量速率 vs 累计计数器)
                flow_col = min(high_card, key=lambda c: df[c].max())
            else:
                flow_col = numeric_value_cols[0]
    elif flow_col in numeric_value_cols and len(numeric_value_cols) > 1:
        # 已识别 flow 但有多个数值列，验证是否需要重新选
        high_card = [c for c in numeric_value_cols
                     if df[c].nunique(dropna=True) > 10]
        if len(high_card) > 1:
            flow_col = min(high_card, key=lambda c: df[c].max())

    return time_col, flow_col


def _classify_columns(df: pd.DataFrame, time_col: str
                      ) -> Tuple[List[str], List[str], List[str], Dict[str, str]]:
    """自动分类非时间列：返回 (desc_cols, value_cols, dropped_cols, display_names)。

    - 空表头列(Unnamed:/空/nan) → dropped(字段名为空则不导入)
    - 纯序号列(1..N) → 丢弃(无实际意义)
    - 高基数数值列 → value_cols(作为透视值)
    - 高基数日期时间列 → 丢弃
    - 其余 → desc_cols(测点标识，构成多级表头)
    """
    n_rows = len(df)
    other_cols = [c for c in df.columns if c != time_col]
    display_names: Dict[str, str] = {}
    dropped_cols: List[str] = []
    for c in other_cols:
        cs = str(c).strip()
        if cs.startswith("Unnamed:") or cs == "" or cs == "nan":
            dropped_cols.append(c)
            continue
        display_names[c] = cs
    desc_cols: List[str] = []
    value_cols: List[str] = []
    seen = set()
    for c in other_cols:
        if c in dropped_cols or c in seen:
            continue
        seen.add(c)
        col = df[c]
        if isinstance(col, pd.DataFrame):
            col = col.iloc[:, 0]
        if col.notna().sum() == 0:
            continue
        nu = col.nunique(dropna=True)
        is_numeric = pd.api.types.is_numeric_dtype(col)
        is_datetime = pd.api.types.is_datetime64_any_dtype(col)
        if is_numeric and nu <= 500:
            vals = sorted(col.dropna().unique())
            if vals == list(range(1, len(vals) + 1)):
                continue
        if is_numeric and nu > 50 and (nu / max(n_rows, 1)) >= 0.01:
            value_cols.append(c)
        elif is_datetime and nu > 50:
            continue
        else:
            desc_cols.append(c)
    return desc_cols, value_cols, dropped_cols, display_names


def _pivot_to_wide(df: pd.DataFrame, time_col: str, flow_col: str,
                   value_cols: Optional[List[str]] = None
                   ) -> Tuple[pd.DataFrame, List[str], List[List[Dict[str, Any]]]]:
    """行转列：参考用户提供的行转列参考文档结构。

    结构（与参考文档一致）：
      - 描述性字段（序号、装置、侧线、物料、位号、进出类型、来源或去向等）→ 多级列头
      - 每个测点占一列，列头从上到下依次为各描述字段的取值
      - 数值字段 → 透视为数据，每个时间行横向展开各测点的值
      - 末尾增加汇总列：进总、出总、出/进
    全程沿用原始列名，不重命名、不丢弃字段。
    value_cols 显式指定时，仅透视用户确认的数值列，避免源数据中计算列污染。
    """
    work = df.copy()
    other_cols = [c for c in work.columns if c != time_col]

    if value_cols is None:
        # 默认路径：自动分类（与历史行为一致）
        desc_cols, auto_values, dropped_cols, display_names = _classify_columns(work, time_col)
        value_cols = auto_values
    else:
        # 显式确认路径：数值列仅取用户确认且实际存在的列；
        # 描述列 = 其余非时间、非数值、非全空列（尊重用户确认，不再自动丢弃序号列）。
        dropped_cols: List[str] = []
        display_names: Dict[str, str] = {}
        for c in other_cols:
            cs = str(c).strip()
            if cs.startswith("Unnamed:") or cs == "" or cs == "nan":
                dropped_cols.append(c)
                continue
            display_names[c] = cs
        value_cols = [c for c in value_cols if c in work.columns and c not in dropped_cols]
        # 高基数列（唯一值接近时间点数，如另一个时间列）不应作为描述列，否则透视爆炸
        n_times = work[time_col].nunique(dropna=True) if time_col in work.columns else len(work)
        desc_cols: List[str] = []
        for c in other_cols:
            if c in dropped_cols or c in value_cols:
                continue
            if work[c].notna().sum() == 0:
                continue
            nu = work[c].nunique(dropna=True)
            if nu > max(500, n_times * 0.5):
                # 高基数列：日期时间型直接跳过；其他型也跳过避免组合爆炸
                continue
            desc_cols.append(c)

    if not desc_cols:
        cols_out = [time_col] + other_cols
        out_df = work[cols_out].copy()
        header_rows = [[{"text": str(c), "rowspan": 2} for c in cols_out]]
        return out_df, cols_out, header_rows

    if not value_cols:
        value_cols = [flow_col] if flow_col else []

    # 构造测点键
    parts = [work[c].astype(str).where(work[c].notna(), "") for c in desc_cols]
    work["_mp_key"] = parts[0].copy()
    for part in parts[1:]:
        work["_mp_key"] = work["_mp_key"].str.cat(part, sep="||")

    mp_meta = work.groupby("_mp_key", sort=False)[desc_cols].first()
    # 测点列顺序 = 原始数据中首次出现的顺序（不按字段值排序）
    ordered_keys = mp_meta.index.tolist()

    # 识别进出类型列（用于汇总）
    io_col_name: Optional[str] = None
    for c in desc_cols:
        vals = set(work[c].dropna().astype(str).unique())
        if vals <= {"进", "出"} and vals:
            io_col_name = c
            break
    in_keys = [k for k in ordered_keys
               if io_col_name and str(mp_meta.loc[k, io_col_name]) == "进"]
    out_keys = [k for k in ordered_keys
                if io_col_name and str(mp_meta.loc[k, io_col_name]) == "出"]

    # 透视每个数值列
    all_blocks: List[pd.DataFrame] = []
    vlabels: List[str] = []  # 展示用的数值列名
    numeric_value_cols: List[str] = []
    for vcol in value_cols:
        if vcol not in work.columns:
            continue
        # Ensure the column is numeric; coerce object columns to numeric
        if not pd.api.types.is_numeric_dtype(work[vcol]):
            try:
                work[vcol] = pd.to_numeric(work[vcol], errors="coerce")
            except Exception:
                continue
        if work[vcol].notna().sum() == 0:
            continue
        numeric_value_cols.append(vcol)
        vlabel = display_names.get(vcol, str(vcol))
        vlabels.append(vlabel)
        pv = work.pivot_table(index=time_col, columns="_mp_key",
                              values=vcol, aggfunc="sum")
        pv = pv.reindex(columns=ordered_keys)
        pv.columns = [f"{vlabel}||{k}" for k in ordered_keys]
        all_blocks.append(pv)
    value_cols = numeric_value_cols

    # 列顺序按测点分组：每个测点下依次排列所有数值列，
    # 这样相同描述字段（装置/侧线/物料等）下的多个数值列并排显示。
    grouped_cols: List[str] = []
    for key in ordered_keys:
        for vlabel in vlabels:
            grouped_cols.append(f"{vlabel}||{key}")

    out_df = pd.concat(all_blocks, axis=1) if all_blocks else pd.DataFrame()
    if grouped_cols:
        out_df = out_df[grouped_cols]
    out_df.insert(0, time_col, out_df.index)
    out_df = out_df.reset_index(drop=True)
    flat_cols: List[str] = [time_col] + grouped_cols

    # 汇总列：优先选名称含 bal 的数值列（物料平衡值）；无则用第一个数值列
    summary_orig = None
    for vc in value_cols:
        if "bal" in str(vc).lower():
            summary_orig = vc
            break
    if summary_orig is None:
        summary_orig = value_cols[0] if value_cols else None
    summary_vlabel = display_names.get(summary_orig, str(summary_orig)) if summary_orig else None
    has_summary = bool(summary_vlabel and io_col_name)
    if has_summary:
        summary_idx = value_cols.index(summary_orig)
        summary_pv = all_blocks[summary_idx]
        in_cols = [f"{summary_vlabel}||{k}" for k in in_keys]
        out_cols = [f"{summary_vlabel}||{k}" for k in out_keys]
        in_sum = summary_pv[in_cols].sum(axis=1, skipna=True)
        out_sum = summary_pv[out_cols].sum(axis=1, skipna=True)
        in_col = f"{summary_vlabel}||进总"
        out_col = f"{summary_vlabel}||出总"
        ratio_col = f"{summary_vlabel}||总收率"
        out_df[in_col] = in_sum.values
        out_df[out_col] = out_sum.values
        # 总收率 = 出总/进总×100，百分比保留两位小数
        out_df[ratio_col] = (out_sum / in_sum.replace(0, pd.NA) * 100.0).round(2).values
        flat_cols.extend([in_col, out_col, ratio_col])

    # 描述列也用展示名
    desc_display = [display_names.get(c, str(c)) for c in desc_cols]
    header_rows = _build_wide_header_rows(time_col, desc_cols, desc_display,
                                          mp_meta, ordered_keys, vlabels,
                                          has_summary=has_summary)
    return out_df, flat_cols, header_rows


def _build_wide_header_rows(time_col: str, desc_cols: List[str],
                            desc_display: List[str],
                            mp_meta: pd.DataFrame,
                            ordered_keys: List[str], value_cols: List[str],
                            has_summary: bool = False
                            ) -> List[List[Dict[str, Any]]]:
    """多级列头：每行对应一个描述字段，最后一行为数值字段名。

    结构（多数值列时按测点分组，描述字段跨数值列合并）：
      第1行: 装置   | 测点1装置(colspan=N) | 测点2装置(colspan=N) | ...
      第2行: 侧线   | 测点1侧线(colspan=N) | 测点2侧线(colspan=N) | ...
      ...
      第N行: 日期时间 | 数值列1|数值列2|.. | 数值列1|数值列2|.. | ... | 进总 | 出总 | 出/进
    其中 N = 数值列个数。单数值列时 colspan=1，与历史行为一致。
    desc_cols 为原始列名(用于 mp_meta 查找)，desc_display 为展示名。
    """
    n_val = max(len(value_cols), 1)
    rows: List[List[Dict[str, Any]]] = []

    for dc, dd in zip(desc_cols, desc_display):
        row: List[Dict[str, Any]] = [{"text": str(dd)}]
        for key in ordered_keys:
            val = mp_meta.loc[key, dc]
            cell: Dict[str, Any] = {"text": "" if pd.isna(val) else str(val)}
            if n_val > 1:
                cell["colspan"] = n_val
            row.append(cell)
        # 汇总列上方留空，与数值行的进总/出总/出/进对齐
        if has_summary:
            for _ in range(3):
                row.append({"text": ""})
        rows.append(row)

    # 数值行：按测点分组，每个测点下依次列出所有数值列名
    value_row: List[Dict[str, Any]] = [{"text": str(time_col)}]
    for _key in ordered_keys:
        for vlabel in value_cols:
            value_row.append({"text": str(vlabel)})
    if has_summary:
        value_row.append({"text": "进总"})
        value_row.append({"text": "出总"})
        value_row.append({"text": "总收率"})
    rows.append(value_row)

    return rows


def _wide_rows(df: pd.DataFrame) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for _, row in df.iterrows():
        d: Dict[str, Any] = {}
        for col in df.columns:
            d[str(col)] = _val_to_jsonable(row.get(col))
        out.append(d)
    return out


# ---------------------------------------------------------------------------
# 主校验入口
# ---------------------------------------------------------------------------
@dataclass
class _Prepared:
    """读文件 + 预处理后的中间结果，供校验与导出共用同一转换路径。"""
    df: pd.DataFrame
    mapping: Dict[str, str]
    original_columns: List[str]
    adjustments: List[str]
    errors: List[str]
    time_col: Optional[str]
    flow_col: Optional[str]
    sheet_name: Optional[str] = None


def _prepare_long_df(file_path: str, sheet_name: Optional[str] = None,
                      max_rows: int = 200) -> _Prepared:
    """Read Excel and produce long-format prepared data.
    
    Uses max_rows limit for fast detection. Full data is read during actual import.
    """
    adjustments: List[str] = []
    
    if sheet_name is not None:
        try:
            df = pd.read_excel(file_path, sheet_name=sheet_name, nrows=max_rows)
        except Exception as e:
            return _Prepared(
                df=pd.DataFrame(), mapping={}, original_columns=[],
                adjustments=[], errors=["Read Excel failed: " + str(e)],
                time_col=None, flow_col=None,
            )
        result = _prepare_from_df(df, adjustments)
        result.sheet_name = sheet_name
        # If no time column detected, try extracting date from sheet header
        if result.time_col is None:
            header_date = _extract_date_from_header(file_path, sheet_name)
            if header_date is not None:
                col_name = "_extracted_date"
                result.df[col_name] = header_date
                result.mapping[col_name] = "time"
                result.time_col = col_name
                result.adjustments = (result.adjustments or []) + [
                    "Extracted date from sheet header: " + str(header_date)[:10]]
        return result
    
    # No sheet specified - get sheet names and try each with limited rows
    try:
        xl = pd.ExcelFile(file_path)
        sheet_names = xl.sheet_names
    except Exception as e:
        return _Prepared(
            df=pd.DataFrame(), mapping={}, original_columns=[],
            adjustments=[], errors=["Read Excel failed: " + str(e)],
            time_col=None, flow_col=None,
        )
    
    # Quick-scan each sheet with limited rows
    best_sheet = None
    best_result = None
    for sn in sheet_names[:3]:
        try:
            df_sample = xl.parse(sheet_name=sn, nrows=10)
            test = _prepare_from_df(df_sample, [])
            if test.time_col is not None and test.flow_col is not None:
                best_sheet = sn
                best_result = test
                break
            if best_result is None and test.time_col is not None:
                best_sheet = sn
                best_result = test
        except Exception:
            continue
    
    # Fallback: use first sheet
    if best_result is None:
        try:
            df = xl.parse(sheet_name=sheet_names[0], nrows=max_rows)
            best_result = _prepare_from_df(df, adjustments)
            best_sheet = sheet_names[0]
        except Exception as e:
            return _Prepared(
                df=pd.DataFrame(), mapping={}, original_columns=[],
                adjustments=[], errors=["Read Excel failed: " + str(e)],
                time_col=None, flow_col=None,
            )
    
    if best_sheet != sheet_names[0]:
        best_result.adjustments = (best_result.adjustments or []) + ["Auto-selected sheet: " + str(best_sheet)]
    
    best_result.sheet_name = best_sheet
    # If no time column detected, try extracting date from sheet header
    if best_result.time_col is None:
        header_date = _extract_date_from_header(file_path, best_sheet)
        if header_date is not None:
            col_name = "_extracted_date"
            best_result.df[col_name] = header_date
            best_result.mapping[col_name] = "time"
            best_result.time_col = col_name
            best_result.adjustments = (best_result.adjustments or []) + [
                "Extracted date from sheet header: " + str(header_date)[:10]]
    
    return best_result


def _prepare_long_df_full(file_path: str, sheet_name: Optional[str] = None) -> _Prepared:
    """Read full Excel data (used during actual import after field confirmation)."""
    adjustments: List[str] = []
    try:
        if sheet_name is not None:
            df = pd.read_excel(file_path, sheet_name=sheet_name)
        else:
            df = pd.read_excel(file_path)
    except Exception as e:
        return _Prepared(
            df=pd.DataFrame(), mapping={}, original_columns=[],
            adjustments=[], errors=["Read Excel failed: " + str(e)],
            time_col=None, flow_col=None,
        )
    result = _prepare_from_df(df, adjustments)
    result.sheet_name = sheet_name
    # If no time column detected, try extracting date from sheet header
    if result.time_col is None:
        header_date = _extract_date_from_header(file_path, sheet_name)
        if header_date is not None:
            col_name = "_extracted_date"
            result.df[col_name] = header_date
            result.mapping[col_name] = "time"
            result.time_col = col_name
            result.adjustments = (result.adjustments or []) + [
                "Extracted date from sheet header: " + str(header_date)[:10]]
    return result


def _extract_date_from_header(file_path: str, sheet_name=None) -> Optional[Any]:
    """Scan first 5 rows of Excel for a date-like value (Excel serial number or date string).
    Returns the detected date value or None.
    """
    try:
        read_kwargs = {"header": None, "nrows": 5}
        if sheet_name is not None:
            read_kwargs["sheet_name"] = sheet_name
        df_raw = pd.read_excel(file_path, **read_kwargs)
        # Scan all cells in first 5 rows for date-like values
        for r in range(min(5, len(df_raw))):
            for col_idx in range(len(df_raw.columns)):
                val = df_raw.iloc[r, col_idx]
                if pd.isna(val):
                    continue
                # Excel serial number
                is_num = pd.api.types.is_number(val)
                if is_num and not isinstance(val, bool):
                    try:
                        fv = float(val)
                    except Exception:
                        continue
                    if 40000 <= fv <= 60000:
                        try:
                            dt = datetime(1899, 12, 30) + timedelta(days=float(val))
                            if 2000 <= dt.year <= 2100:
                                return dt
                        except Exception:
                            pass
                # Date/datetime object
                if isinstance(val, (datetime, date)):
                    return val
                # Date string
                if isinstance(val, str):
                    s = val.strip()
                    import re
                    if re.match(r"^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}", s):
                        try:
                            return pd.to_datetime(s)
                        except Exception:
                            pass
    except Exception:
        pass
    return None


def _preprocess_tank_data(df, adjustments):
    """Preprocess tank-table-specific data.

    Tank tables have a complex multi-row header:
      Row 0: Title (e.g. 大榭石化调度罐表（一）)
      Row 1: Department + date serial number
      Row 2: Main header (序号, 罐号, 安全高度, ...)
      Row 3: Sub-header (液位, 罐量, ...)
      Row 4+: Data (with merged cells and interspersed repeated headers)

    Steps:
      1. Detect header row (contains '序号' and '罐号')
      2. Build column names from main header + sub-header (multi-level)
      3. Forward-fill merged cells (un-merge)
      4. Drop rows where 序号 is not numeric (title/header rows in data)
      5. Drop completely empty rows
    """
    if len(df) == 0:
        return df

    # --- Step 1a: Check if data is already standard long-table format ---
    # (header at row 0 with 日期 + 罐号 columns, no multi-row header)
    col_strs = [str(c).strip() for c in df.columns]
    has_date = any("日期" in c for c in col_strs)
    has_tank = any("罐号" in c or "罐  号" in c for c in col_strs)
    if has_date and has_tank:
        # Already standard long table — just clean up empty columns and forward-fill
        # Drop columns that are completely empty or all-NaN unnamed columns
        cols_to_drop = []
        for c in df.columns:
            cs = str(c).strip()
            if cs.startswith("Unnamed:") and df[c].isna().all():
                cols_to_drop.append(c)
        if cols_to_drop:
            df = df.drop(columns=cols_to_drop)
            adjustments.append("Dropped %d empty columns." % len(cols_to_drop))
        # Forward-fill merged cells
        filled_count = 0
        for col in df.columns:
            if str(col) == "_extracted_date":
                continue
            na_before = df[col].isna().sum()
            df[col] = df[col].ffill()
            filled_count += na_before - df[col].isna().sum()
        if filled_count > 0:
            adjustments.append("Filled %d merged cells." % filled_count)
        # Drop completely empty rows
        before = len(df)
        df = df.dropna(how="all").reset_index(drop=True)
        dropped = before - len(df)
        if dropped > 0:
            adjustments.append("Dropped %d empty rows." % dropped)
        return df

    # --- Step 1b: Detect multi-row header containing '序号' and '罐号' ---
    header_row_idx = None
    n_cols = df.shape[1]
    for i in range(min(15, len(df))):
        row_vals = set()
        for j in range(min(n_cols, 20)):
            v = df.iloc[i, j]
            if pd.notna(v):
                row_vals.add(str(v).strip())
        if "序号" in row_vals and ("罐号" in row_vals or "罐  号" in row_vals):
            header_row_idx = i
            break

    if header_row_idx is None:
        # Not tank data - fall back to legacy detection
        return _preprocess_tank_data_legacy(df, adjustments)

    # --- Step 2: Build column names from main header + optional sub-header ---
    main_header = list(df.iloc[header_row_idx])
    # Forward-fill main header to handle merged header cells (e.g. 前尺 spanning 2 cols)
    main_filled = []
    last_val = None
    for v in main_header:
        if pd.notna(v) and str(v).strip():
            last_val = str(v).strip()
        main_filled.append(last_val)

    # Check if the next row is a sub-header (has values where main header was NaN)
    sub_idx = header_row_idx + 1
    data_start = header_row_idx + 1
    new_columns = []
    if sub_idx < len(df):
        sub_row = list(df.iloc[sub_idx])
        has_sub = False
        for j in range(min(len(sub_row), len(main_header))):
            sv = sub_row[j]
            mv = main_header[j]
            if pd.notna(sv) and str(sv).strip() and (mv is None or pd.isna(mv)):
                has_sub = True
                break
        if has_sub:
            data_start = sub_idx + 1
            for j in range(len(main_filled)):
                sv = sub_row[j] if j < len(sub_row) else None
                sv = str(sv).strip() if (pd.notna(sv) and str(sv).strip()) else ""
                if sv:
                    new_columns.append(f"{main_filled[j]}_{sv}" if main_filled[j] else sv)
                else:
                    new_columns.append(main_filled[j] or f"Unnamed_{j}")
        else:
            new_columns = [h or f"Unnamed_{j}" for j, h in enumerate(main_filled)]
    else:
        new_columns = [h or f"Unnamed_{j}" for j, h in enumerate(main_filled)]

    # Deduplicate column names
    seen = {}
    deduped = []
    for c in new_columns:
        if c in seen:
            seen[c] += 1
            deduped.append(f"{c}_{seen[c]}")
        else:
            seen[c] = 0
            deduped.append(c)

    # Apply new column names and drop header rows
    df = df.iloc[data_start:].copy()
    df.columns = deduped
    df = df.reset_index(drop=True)
    adjustments.append("Detected tank table header at row %d." % (header_row_idx + 1))

    # --- Step 3: Forward-fill ONLY the seq column (to preserve merged-cell rows) ---
    seq_col = None
    for col in df.columns:
        if str(col).strip() == "序号":
            seq_col = col
            break
    if seq_col is not None:
        df[seq_col] = df[seq_col].ffill()

    # --- Step 4: Drop rows where 序号 is still not numeric (title/header rows) ---
    if seq_col is not None:
        seq_num = pd.to_numeric(df[seq_col], errors="coerce")
        before = len(df)
        df = df[seq_num.notna()].copy()
        df[seq_col] = seq_num[seq_num.notna()].astype(int)
        dropped = before - len(df)
        if dropped > 0:
            adjustments.append("Dropped %d non-data rows (non-numeric seq)." % dropped)

    # --- Step 5: Forward-fill remaining merged cells (now safe: header rows removed) ---
    filled_count = 0
    for col in df.columns:
        if str(col) == "_extracted_date" or col == seq_col:
            continue
        na_before = df[col].isna().sum()
        df[col] = df[col].ffill()
        filled_count += na_before - df[col].isna().sum()
    if filled_count > 0:
        adjustments.append("Filled %d merged cells." % filled_count)

    # Convert numeric-looking columns to proper numeric dtype
    for col in df.columns:
        if str(col) == "_extracted_date":
            continue
        if df[col].dtype == object:
            try:
                df[col] = pd.to_numeric(df[col], errors="raise")
            except (ValueError, TypeError):
                pass

    # --- Step 6: Drop completely empty rows ---
    before = len(df)
    df = df.dropna(how="all").reset_index(drop=True)
    dropped = before - len(df)
    if dropped > 0:
        adjustments.append("Dropped %d empty rows." % dropped)

    return df


def _preprocess_tank_data_legacy(df, adjustments):
    """Legacy fallback: detect seq column, forward-fill, drop empty rows."""
    if len(df) == 0:
        return df
    seq_col = None
    for col in df.columns:
        vals = df[col].dropna()
        if len(vals) == 0:
            continue
        num_vals = []
        for v in vals:
            try:
                fv = float(v)
                if fv == int(fv):
                    num_vals.append(int(fv))
            except (ValueError, TypeError):
                continue
        if len(num_vals) >= 3 and num_vals[0] == 1:
            sequential_count = sum(1 for i, v in enumerate(num_vals) if v == i + 1)
            if sequential_count >= len(num_vals) * 0.7:
                seq_col = col
                break
    if seq_col is not None:
        filled_count = 0
        for col in df.columns:
            if str(col) == "_extracted_date":
                continue
            na_before = df[col].isna().sum()
            df[col] = df[col].ffill()
            filled_count += na_before - df[col].isna().sum()
        if filled_count > 0:
            adjustments.append("Filled %d merged cells (forward-filled)." % filled_count)
        before_count = len(df)
        all_empty = df.isna().all(axis=1)
        df = df[~all_empty].copy()
        dropped = before_count - len(df)
        if dropped > 0:
            adjustments.append("Dropped %d completely empty rows." % dropped)
    return df


def _prepare_from_df(df, adjustments):
    # Preprocess tank-table data: fill merged cells, drop empty-sequence rows
    df = _preprocess_tank_data(df, adjustments)
    original_columns = [str(c) for c in df.columns]
    mapping = _build_column_mapping(original_columns, df)

    # 若缺少必填角色，尝试检测并逆透视宽表
    # 但罐表数据（有日期+罐号列）本身就是长表，不应逆透视
    col_strs_set = {str(c).strip() for c in df.columns}
    is_tank_long = any("日期" in c for c in col_strs_set) and any("罐号" in c or "罐  号" in c for c in col_strs_set)
    missing_after_direct = [
        f.key for f in STANDARD_FIELDS if f.required and f.key not in mapping.values()
    ]
    if missing_after_direct and not is_tank_long:
        wide_result = _detect_wide_table(df, mapping)
        if wide_result:
            time_col_w, id_cols_w, value_cols_w = wide_result
            adjustments.append(f"检测到宽表结构（以「{time_col_w}」为时间轴），执行逆透视(宽表转长表)。")
            df = _melt_wide_table(df, time_col_w, id_cols_w, value_cols_w)
            original_columns = [str(c) for c in df.columns]
            mapping = _build_column_mapping(original_columns, df)

    # 兜底识别时间列
    if "time" not in mapping.values():
        for c in df.columns:
            try:
                if df[c].apply(_looks_like_time).mean() > 0.5:
                    mapping[str(c)] = "time"
                    adjustments.append(f"未发现时间列名，根据数据内容将「{c}」识别为时间列。")
                    break
            except Exception:
                continue

    # 进出类型归一化（保留原始取值语义为 进/出，不改列名）
    present_keys = set(mapping.values())
    if "io_type" in present_keys:
        io_col = next(c for c, k in mapping.items() if k == "io_type")
        before = set(df[io_col].dropna().astype(str).unique())
        df[io_col] = df[io_col].apply(lambda x: _classify_io(str(x)) if pd.notna(x) else x)
        after = set(df[io_col].dropna().astype(str).unique())
        if before and before != after:
            adjustments.append(f"进出类型取值已归一化: {sorted(before)} -> {sorted(after)}")

    time_col, flow_col = _pick_time_and_flow(df, mapping)

    # Validate time column: if values are mostly invalid (NaT or before 2000), discard
    # Use _convert_time_column (not raw pd.to_datetime) so integer YYYYMMDD
    # values like 20260717 are parsed as dates, not as nanoseconds-since-epoch.
    if time_col is not None:
        try:
            time_vals = _convert_time_column(df[time_col])
            valid_ratio = time_vals.notna().sum() / max(len(time_vals), 1)
            if valid_ratio < 0.5:
                time_col = None
                mapping = {k: v for k, v in mapping.items() if v != "time"}
            else:
                # Check if dates are reasonable (not 1970 epoch from small numbers)
                valid_dates = pd.to_datetime(time_vals, errors="coerce").dropna()
                if len(valid_dates) > 0:
                    min_year = valid_dates.dt.year.min()
                    if min_year < 2000:
                        time_col = None
                        mapping = {k: v for k, v in mapping.items() if v != "time"}
        except Exception:
            time_col = None
            mapping = {k: v for k, v in mapping.items() if v != "time"}

    if time_col is not None:
        converted = _convert_time_column(df[time_col])
        if not converted.equals(df[time_col]):
            df[time_col] = converted
            adjustments.append(f"时间列「{time_col}」已从数值/字符串格式转换为标准日期格式。")

    return _Prepared(
        df=df, mapping=mapping, original_columns=original_columns,
        adjustments=adjustments, errors=[], time_col=time_col, flow_col=flow_col,
    )


def transform_to_wide_df(file_path: str, sheet_name: Optional[str] = None,
                         value_cols: Optional[List[str]] = None
                         ) -> Tuple[Optional[pd.DataFrame], List[str], List[List[Dict[str, Any]]], str, List[str]]:
    """导出用：返回完整宽表 DataFrame、扁平列名、多级表头结构、时间列名、错误列表。

    转换路径与 validate_excel 完全一致，只是不做预览截断，返回全部行。
    """
    prep = _prepare_long_df(file_path, sheet_name)
    if prep.errors:
        return None, [], [], prep.time_col or "", prep.errors
    if prep.time_col is None or prep.flow_col is None:
        return None, [], [], prep.time_col or "", ["无法识别时间列或数值列，无法进行行列转换。"]
    try:
        wide_df, flat_cols, header_rows = _pivot_to_wide(prep.df, prep.time_col, prep.flow_col, value_cols)
        return wide_df, flat_cols, header_rows, prep.time_col, []
    except Exception as e:
        return None, [], [], prep.time_col or "", [f"宽表转换失败: {e}"]


def field_structure_from_prep(prep: "_Prepared") -> Dict[str, Any]:
    """从已预处理的长表(_Prepared)提取字段结构，供"导入前字段确认"使用。

    返回字段清单（按原始顺序，已排除空表头列）、各字段识别角色、数据类型、
    样例值，以及自动识别的时间列/数值列。全程不改列名。复用已有预处理结果，
    避免重复解析 Excel。
    """
    df = prep.df
    mapping = prep.mapping
    time_col = prep.time_col
    flow_col = prep.flow_col
    if time_col is not None and time_col in df.columns:
        _desc, auto_values, _dropped, _dn = _classify_columns(df, time_col)
    else:
        auto_values = []
    fields: List[Dict[str, Any]] = []
    field_order: List[str] = []
    _empty_counter = 0
    _seen_cols = set()
    for c in df.columns:
        cs = str(c).strip()
        if cs in _seen_cols:
            continue
        _seen_cols.add(cs)
        is_empty_header = cs.startswith("Unnamed:") or cs == "" or cs == "nan"
        # 空字段名列也显示出来（有数据时），用可读占位名（编号唯一），默认不包含
        if is_empty_header:
            _empty_counter += 1
            display_name = f"(空字段名{_empty_counter})"
        else:
            display_name = str(c)
        # 空字段名列若全空则跳过
        _col_data = df[c]
        if isinstance(_col_data, pd.DataFrame):
            _col_data = _col_data.iloc[:, 0]
        if is_empty_header and _col_data.notna().sum() == 0:
            _empty_counter -= 1
            continue
        role = mapping.get(c, "")
        role_desc = ROLE_DESC.get(role, "描述列") if role else "描述列"
        try:
            dtype = str(df[c].dtype)
        except Exception:
            dtype = ""
        try:
            raw = df[c].dropna().unique()[:4]
            samples = [_val_to_jsonable(v) for v in raw]
        except Exception:
            samples = []
        fields.append({
            "name": display_name, "orig_name": str(c),
            "role": role, "role_desc": role_desc,
            "dtype": dtype, "samples": samples,
            "is_time": (c == time_col), "is_value": (c in auto_values),
            "is_empty_header": is_empty_header,
        })
        field_order.append(str(c))
    return {
        "fields": fields, "field_order": field_order,
        "time_col": time_col, "flow_col": flow_col, "value_cols": auto_values,
        "adjustments": prep.adjustments, "errors": [],
        "original_columns": prep.original_columns,
    }


def extract_field_structure(file_path: str, sheet_name: Optional[str] = None) -> Dict[str, Any]:
    """读取 Excel 并提取字段结构（不保存数据），供"导入前字段确认"使用。"""
    prep = _prepare_long_df(file_path, sheet_name)
    if prep.errors:
        return {
            "fields": [], "field_order": [], "time_col": None, "flow_col": None,
            "value_cols": [], "adjustments": prep.adjustments, "errors": list(prep.errors),
            "original_columns": prep.original_columns,
        }
    return field_structure_from_prep(prep)


def validate_excel(file_path: str, sheet_name: Optional[str] = None) -> ValidationReport:
    prep = _prepare_long_df(file_path, sheet_name)
    errors: List[str] = list(prep.errors)
    adjustments: List[str] = list(prep.adjustments)
    df = prep.df
    mapping = prep.mapping
    original_columns = prep.original_columns
    time_col = prep.time_col
    flow_col = prep.flow_col

    if errors:
        return ValidationReport(
            is_standard=False, needs_adjustment=False, column_order_ok=False,
            original_columns=[], column_mapping={}, missing_required=[f.label for f in STANDARD_FIELDS if f.required],
            adjustments=adjustments, row_count=0, preview_columns=[], preview_rows=[],
            wide_columns=[], wide_preview_rows=[], errors=errors,
        )

    present_keys = set(mapping.values())
    # 必填字段检查仅作参考，不阻止宽表生成
    missing_required = [f.label for f in STANDARD_FIELDS if f.required and f.key not in present_keys]

    # 列顺序：第一列是否为时间
    first_mapped_key = None
    for c in original_columns:
        if c in mapping:
            first_mapped_key = mapping[c]
            break
    column_order_ok = (first_mapped_key == "time")
    if (not column_order_ok) and ("time" in present_keys):
        adjustments.append("原始数据时间列不在第一列，已标记需要调整列顺序。")

    is_standard = (not missing_required) and (not adjustments)
    needs_adjustment = bool(adjustments)

    # 宽表：保留全部原始字段，横竖转换
    wide_columns: List[str] = []
    wide_preview_rows: List[Dict[str, Any]] = []
    wide_header_rows: List[List[Dict[str, Any]]] = []
    time_col_name: str = time_col or ""
    time_min: Optional[str] = None
    time_max: Optional[str] = None
    if time_col is not None and flow_col is not None:
        try:
            wide_df, wide_columns, wide_header_rows = _pivot_to_wide(df, time_col, flow_col)
            wide_preview_rows = _wide_rows(wide_df)
            if time_col in wide_df.columns:
                tser = pd.to_datetime(wide_df[time_col], errors="coerce")
                if tser.notna().any():
                    time_min = tser.min().strftime("%Y-%m-%d")
                    time_max = tser.max().strftime("%Y-%m-%d")
        except Exception as e:
            errors.append(f"宽表转换失败: {e}")

    preview_columns = [str(c) for c in df.columns]
    return ValidationReport(
        is_standard=is_standard, needs_adjustment=needs_adjustment,
        column_order_ok=column_order_ok, original_columns=original_columns,
        column_mapping=mapping, missing_required=missing_required,
        adjustments=adjustments, row_count=len(df),
        preview_columns=preview_columns, preview_rows=_rows_to_dicts(df, preview_columns),
        wide_columns=wide_columns, wide_header_rows=wide_header_rows,
        wide_preview_rows=wide_preview_rows, errors=errors,
        time_col=time_col_name, time_min=time_min, time_max=time_max,
    )


def report_to_dict(report: ValidationReport) -> Dict[str, Any]:
    d = asdict(report)
    # column_mapping：对外展示为 {原始列名: 角色描述}。
    # 角色描述统一使用"X列"措辞（如"装置列""侧线列"），明确表示这是该列承担的
    # 角色，而非把原始列名重命名成别的字段名。绝不暴露内部 key、不改列名。
    d["column_mapping"] = {orig: ROLE_DESC.get(role, "其他列") for orig, role in report.column_mapping.items()}
    return d
