# -*- coding: utf-8 -*-
"""收率预测模型：基于进料占比预测出料收率，量化每项进料对收率的影响。

特征工程：进料占比 x_i = 进料i流量 / 总进料流量
目标变量：出料收率 y_j = 出料j流量 / 总进料流量
模型：岭回归(可解释) + Lasso(特征筛选) + 梯度提升+SHAP(非线性,数据足时)
"""
import os
import sys
import pickle
import warnings
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

warnings.filterwarnings("ignore")

_DATA_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "data")


def _load_store(source_id: str) -> Optional[Dict[str, Any]]:
    p = os.path.join(_DATA_DIR, f"source_{source_id}.pkl")
    if not os.path.exists(p):
        return None
    with open(p, "rb") as f:
        return pickle.load(f)


def _identify_io_col(df: pd.DataFrame, time_col: str) -> Optional[str]:
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).unique())
        if vals and vals <= {"进", "出"}:
            return c
    return None


def _identify_value_col(store: Dict[str, Any]) -> Optional[str]:
    value_cols = store.get("value_cols") or []
    df = store.get("long_df")
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            return vc
    flow_col = store.get("flow_col")
    candidate = flow_col if (flow_col and flow_col in value_cols) else (value_cols[0] if value_cols else flow_col)
    if candidate and candidate in df.columns:
        return candidate
    return None


def _identify_material_col(df: pd.DataFrame, time_col: str, io_col: str, value_col: str) -> Optional[str]:
    exclude = {time_col, io_col, value_col}
    for vc in [value_col]:
        exclude.add(vc)
    candidates = []
    for c in df.columns:
        if c in exclude:
            continue
        s = str(c).strip().lower()
        vals = df[c].dropna().astype(str)
        if vals.empty:
            continue
        nunique = vals.nunique()
        if nunique >= len(vals) * 0.9 or "code" in s or "_id" in s or s.endswith("id"):
            continue
        try:
            num_ratio = pd.to_numeric(vals, errors="coerce").notna().sum() / len(vals)
            if num_ratio > 0.8:
                continue
        except Exception:
            pass
        priority = 0
        if "display" in s:
            priority = 100
        elif "名称" in str(c) or "物料" in str(c):
            priority = 90
        elif "alias" in s or "侧线" in str(c):
            priority = 80
        candidates.append((priority, c, nunique))
    if not candidates:
        return None
    candidates.sort(key=lambda x: (-x[0], x[2]))
    return candidates[0][1]


def _identify_density_col(df: pd.DataFrame, time_col: str, value_col: str) -> Optional[str]:
    for c in df.columns:
        if c == time_col or c == value_col:
            continue
        cn = str(c).strip().lower()
        if "密度" in str(c) or "density" in cn:
            return c
    return None


def build_feature_matrix(source_id: str, use_lag: bool = True, lag_days: int = 2,
                              use_rolling: bool = False, rolling_window: int = 3) -> Dict[str, Any]:
    """从存储数据构建特征矩阵 X(进料占比) 和目标矩阵 Y(出料收率)。"""
    store = _load_store(source_id)
    if store is None:
        return {"error": f"数据源 {source_id} 不存在"}

    df = store.get("long_df")
    if df is None or df.empty:
        return {"error": "该数据源暂无导入数据"}

    time_col = store.get("time_col")
    if not time_col or time_col not in df.columns:
        return {"error": "未识别到时间列"}

    io_col = _identify_io_col(df, time_col)
    if io_col is None:
        return {"error": "未识别到进出类型列"}

    value_col = _identify_value_col(store)
    if value_col is None:
        return {"error": "未识别到数值列"}

    name_col = _identify_material_col(df, time_col, io_col, value_col)
    if name_col is None:
        return {"error": "未识别到物料名称列"}

    density_col = _identify_density_col(df, time_col, value_col)

    cols_needed = [time_col, io_col, name_col, value_col]
    if density_col and density_col not in cols_needed:
        cols_needed.append(density_col)
    work = df[cols_needed].copy()
    work[time_col] = pd.to_datetime(work[time_col], errors="coerce")
    work[value_col] = pd.to_numeric(work[value_col], errors="coerce")
    work = work.dropna(subset=[time_col, value_col, name_col])
    if work.empty:
        return {"error": "无有效数据行"}

    # 有效质量值（密度换算）
    eff_col = "_eff_value"
    if density_col and density_col in work.columns:
        dens = pd.to_numeric(work[density_col], errors="coerce")
        qty = work[value_col]
        effective = qty.copy()
        mask = dens.notna() & (dens != 0)
        effective[mask] = qty[mask] * dens[mask] / 1000.0
        work[eff_col] = effective
    else:
        work[eff_col] = work[value_col]

    work[name_col] = work[name_col].astype(str).str.strip()

    # 按时间+方向+物料聚合
    grouped = work.groupby([time_col, io_col, name_col])[eff_col].sum().reset_index()

    # 透视：行=时间，列=物料，值=流量
    in_df = grouped[grouped[io_col] == "进"].pivot_table(
        index=time_col, columns=name_col, values=eff_col, aggfunc="sum"
    ).fillna(0)
    out_df = grouped[grouped[io_col] == "出"].pivot_table(
        index=time_col, columns=name_col, values=eff_col, aggfunc="sum"
    ).fillna(0)

    # 对齐时间索引
    common_dates = in_df.index.intersection(out_df.index)
    in_df = in_df.loc[common_dates].sort_index()
    out_df = out_df.loc[common_dates].sort_index()

    # 总进料
    jin_total = in_df.sum(axis=1)
    valid = jin_total > 0
    in_df = in_df[valid]
    out_df = out_df[valid]
    jin_total = jin_total[valid]

    # 进料占比 X = 进料i / 总进料
    X_ratio = in_df.div(jin_total, axis=0)
    # 出料收率 Y = 出料j / 总进料
    Y_yield = out_df.div(jin_total, axis=0) * 100.0

    # 去掉全零列（该物料在所有时间点流量都为0）
    in_mats = [m for m in X_ratio.columns if X_ratio[m].abs().sum() > 1e-6]
    out_mats = [m for m in Y_yield.columns if Y_yield[m].abs().sum() > 1e-6]
    X_ratio = X_ratio[in_mats]
    Y_yield = Y_yield[out_mats]

    has_time = any((d.hour != 0 or d.minute != 0) for d in common_dates)
    granularity = "hourly" if has_time else "daily"
    date_fmt = "%Y-%m-%d %H:%M" if has_time else "%Y-%m-%d"
    date_labels = [d.strftime(date_fmt) for d in X_ratio.index]

    result = {
        "source_id": source_id,
        "granularity": granularity,
        "time_col": time_col,
        "io_col": io_col,
        "value_col": value_col,
        "name_col": name_col,
        "density_col": density_col,
        "in_materials": in_mats,
        "out_materials": out_mats,
        "n_samples": len(X_ratio),
        "date_range": [date_labels[0], date_labels[-1]] if date_labels else [],
        "X_ratio": X_ratio,
        "Y_yield": Y_yield,
        "jin_total": jin_total,
        "date_labels": date_labels,
        "use_lag": use_lag and granularity == "daily",
        "lag_days": lag_days,
        "use_rolling": use_rolling and granularity == "daily",
        "rolling_window": rolling_window,
    }
    return result


def _add_lag_features(X: pd.DataFrame, lag_days: int) -> Tuple[pd.DataFrame, List[str]]:
    """添加滞后特征：x_i(t-k)。返回增强后的特征矩阵和基础特征列名。"""
    base_cols = list(X.columns)
    lag_frames = [X]
    for k in range(1, lag_days + 1):
        lag = X.shift(k)
        lag.columns = [f"{c}_lag{k}" for c in X.columns]
        lag_frames.append(lag)
    enhanced = pd.concat(lag_frames, axis=1)
    # 去掉滞后产生的NaN行
    enhanced = enhanced.dropna()
    return enhanced, base_cols


def fit_ridge_model(fm: Dict[str, Any], target_material: str, alpha: float = 1.0) -> Dict[str, Any]:
    """对单个出料物料拟合岭回归，返回系数和评估指标。"""
    from sklearn.linear_model import Ridge
    from sklearn.model_selection import TimeSeriesSplit
    from sklearn.metrics import r2_score, mean_absolute_error

    X = fm["X_ratio"].copy()
    Y = fm["Y_yield"].copy()

    if target_material not in Y.columns:
        return {"error": f"出料物料 {target_material} 不存在"}

    y = Y[target_material].values

    use_lag = fm.get("use_lag", False)
    use_rolling = fm.get("use_rolling", False)
    base_cols = list(X.columns)

    X_enhanced, _, feature_names, base_cols = _prepare_features(fm, use_lag, use_rolling)
    y_aligned = Y[target_material].loc[X_enhanced.index].values
    X_arr = X_enhanced.values

    n = len(y_aligned)
    if n < 10:
        return {"error": f"样本量不足({n}条)，至少需要10条"}

    # 时间序列交叉验证
    n_splits = min(5, n // 5)
    if n_splits < 2:
        n_splits = 2

    cv_r2_scores = []
    cv_mae_scores = []
    tscv = TimeSeriesSplit(n_splits=n_splits)
    for train_idx, test_idx in tscv.split(X_arr):
        X_tr, X_te = X_arr[train_idx], X_arr[test_idx]
        y_tr, y_te = y_aligned[train_idx], y_aligned[test_idx]
        model = Ridge(alpha=alpha)
        model.fit(X_tr, y_tr)
        y_pred = model.predict(X_te)
        cv_r2_scores.append(r2_score(y_te, y_pred))
        cv_mae_scores.append(mean_absolute_error(y_te, y_pred))

    # 全量拟合
    model_full = Ridge(alpha=alpha)
    model_full.fit(X_arr, y_aligned)
    y_pred_full = model_full.predict(X_arr)

    # 系数：只取基础特征（当前时刻进料占比），滞后特征单独报告
    coefs = {}
    for fname, coef in zip(feature_names, model_full.coef_):
        coefs[fname] = round(float(coef), 6)

    # 基础特征系数（当前进料占比 → 收率影响）
    base_coefs = {}
    for bc in base_cols:
        if bc in coefs:
            base_coefs[bc] = coefs[bc]

    return {
        "target": target_material,
        "n_samples": n,
        "intercept": round(float(model_full.intercept_), 4),
        "coefficients": base_coefs,
        "all_coefficients": coefs,
        "feature_names": feature_names,
        "base_features": base_cols,
        "cv_r2_mean": round(float(np.mean(cv_r2_scores)), 4),
        "cv_r2_std": round(float(np.std(cv_r2_scores)), 4),
        "cv_mae_mean": round(float(np.mean(cv_mae_scores)), 4),
        "train_r2": round(float(r2_score(y_aligned, y_pred_full)), 4),
        "train_mae": round(float(mean_absolute_error(y_aligned, y_pred_full)), 4),
        "y_mean": round(float(np.mean(y_aligned)), 4),
        "y_std": round(float(np.std(y_aligned)), 4),
        "y_min": round(float(np.min(y_aligned)), 4),
        "y_max": round(float(np.max(y_aligned)), 4),
    }


def fit_lasso_model(fm: Dict[str, Any], target_material: str, alpha: float = 0.01) -> Dict[str, Any]:
    """Lasso回归：自动筛选对收率有显著影响的进料。"""
    from sklearn.linear_model import Lasso
    from sklearn.model_selection import TimeSeriesSplit
    from sklearn.metrics import r2_score, mean_absolute_error

    X = fm["X_ratio"].copy()
    Y = fm["Y_yield"].copy()

    if target_material not in Y.columns:
        return {"error": f"出料物料 {target_material} 不存在"}

    use_lag = fm.get("use_lag", False)
    use_rolling = fm.get("use_rolling", False)
    X_enhanced, _, feature_names, _ = _prepare_features(fm, use_lag, use_rolling)
    y = Y[target_material].loc[X_enhanced.index].values
    X_arr = X_enhanced.values
    n = len(y)

    if n < 10:
        return {"error": f"样本量不足({n}条)"}

    model = Lasso(alpha=alpha, max_iter=10000)
    model.fit(X_arr, y)
    y_pred = model.predict(X_arr)

    # 非零系数 = 被选中的关键进料
    selected = {}
    zeroed = []
    for fname, coef in zip(feature_names, model.coef_):
        if abs(coef) > 1e-6:
            selected[fname] = round(float(coef), 6)
        else:
            zeroed.append(fname)

    return {
        "target": target_material,
        "n_samples": n,
        "intercept": round(float(model.intercept_), 4),
        "selected_features": selected,
        "zeroed_features": zeroed,
        "n_selected": len(selected),
        "n_zeroed": len(zeroed),
        "train_r2": round(float(r2_score(y, y_pred)), 4),
        "train_mae": round(float(mean_absolute_error(y, y_pred)), 4),
    }


def fit_all_models(source_id: str, use_lag: bool = True, use_rolling: bool = False) -> Dict[str, Any]:
    """对所有出料物料拟合模型，返回完整结果。"""
    fm = build_feature_matrix(source_id, use_lag=use_lag, use_rolling=use_rolling)
    if "error" in fm:
        return fm

    out_mats = fm["out_materials"]
    ridge_results = {}
    lasso_results = {}

    for mat in out_mats:
        ridge_results[mat] = fit_ridge_model(fm, mat)
        lasso_results[mat] = fit_lasso_model(fm, mat)

    # 构建系数矩阵：行=进料，列=出料
    in_mats = fm["in_materials"]
    coef_matrix = {}
    for im in in_mats:
        coef_matrix[im] = {}
        for om in out_mats:
            rr = ridge_results.get(om, {})
            if "coefficients" in rr and im in rr["coefficients"]:
                coef_matrix[im][om] = rr["coefficients"][im]
            else:
                coef_matrix[im][om] = None

    return {
        "source_id": source_id,
        "granularity": fm["granularity"],
        "n_samples": fm["n_samples"],
        "date_range": fm["date_range"],
        "in_materials": in_mats,
        "out_materials": out_mats,
        "coef_matrix": coef_matrix,
        "ridge_results": ridge_results,
        "lasso_results": lasso_results,
        "feature_info": {
            "time_col": fm["time_col"],
            "io_col": fm["io_col"],
            "value_col": fm["value_col"],
            "name_col": fm["name_col"],
            "density_col": fm["density_col"],
            "use_lag": fm.get("use_lag", False),
            "lag_days": fm.get("lag_days", 2),
            "use_rolling": fm.get("use_rolling", False),
            "rolling_window": fm.get("rolling_window", 3),
        },
        "errors": [],
    }



def _add_rolling_features(X: pd.DataFrame, window: int) -> Tuple[pd.DataFrame, List[str]]:
    """添加滚动均值特征：x_i 的 window 日滑动平均。返回增强后的特征矩阵和基础特征列名。"""
    base_cols = list(X.columns)
    rolling = X.rolling(window=window, min_periods=1).mean()
    rolling.columns = [f"{c}_roll{window}" for c in X.columns]
    enhanced = pd.concat([X, rolling], axis=1)
    return enhanced, base_cols


def _prepare_features(fm: Dict[str, Any], use_lag: bool, use_rolling: bool) -> Tuple[pd.DataFrame, pd.Series, List[str], List[str]]:
    """根据选项构建增强特征矩阵。返回 (X_arr_df, y_series, feature_names, base_feature_names)。"""
    X = fm["X_ratio"].copy()
    Y = fm["Y_yield"].copy()
    target = None  # placeholder

    base_cols = list(X.columns)
    feature_frames = [X]

    if use_rolling and fm.get("use_rolling", False):
        rolling = X.rolling(window=fm.get("rolling_window", 3), min_periods=1).mean()
        rolling.columns = [f"{c}_roll{fm.get('rolling_window', 3)}" for c in X.columns]
        feature_frames.append(rolling)

    if use_lag and fm.get("use_lag", False):
        lag_days = fm.get("lag_days", 2)
        for k in range(1, lag_days + 1):
            lag = X.shift(k)
            lag.columns = [f"{c}_lag{k}" for c in X.columns]
            feature_frames.append(lag)

    X_enhanced = pd.concat(feature_frames, axis=1).dropna()
    feature_names = list(X_enhanced.columns)
    return X_enhanced, Y, feature_names, base_cols


def fit_gb_shap_model(fm: Dict[str, Any], target_material: str,
                      use_lag: bool = True, use_rolling: bool = True) -> Dict[str, Any]:
    """梯度提升树 + SHAP：捕捉非线性关系，量化每项进料的贡献。

    返回 SHAP 值摘要（全局重要性排序）和模型评估指标。
    """
    import shap
    from sklearn.ensemble import GradientBoostingRegressor
    from sklearn.model_selection import TimeSeriesSplit
    from sklearn.metrics import r2_score, mean_absolute_error

    if target_material not in fm["Y_yield"].columns:
        return {"error": f"出料物料 {target_material} 不存在"}

    X_enhanced, Y, feature_names, base_cols = _prepare_features(fm, use_lag, use_rolling)
    y = Y[target_material].loc[X_enhanced.index].values
    X_arr = X_enhanced.values
    n = len(y)

    if n < 20:
        return {"error": f"样本量不足({n}条)，梯度提升至少需要20条"}

    # 时间序列交叉验证
    n_splits = min(5, n // 5)
    if n_splits < 2:
        n_splits = 2
    cv_r2_scores = []
    cv_mae_scores = []
    tscv = TimeSeriesSplit(n_splits=n_splits)
    for train_idx, test_idx in tscv.split(X_arr):
        X_tr, X_te = X_arr[train_idx], X_arr[test_idx]
        y_tr, y_te = y[train_idx], y[test_idx]
        m = GradientBoostingRegressor(n_estimators=100, max_depth=3, learning_rate=0.1, random_state=42)
        m.fit(X_tr, y_tr)
        yp = m.predict(X_te)
        cv_r2_scores.append(r2_score(y_te, yp))
        cv_mae_scores.append(mean_absolute_error(y_te, yp))

    # 全量拟合
    model = GradientBoostingRegressor(n_estimators=100, max_depth=3, learning_rate=0.1, random_state=42)
    model.fit(X_arr, y)
    y_pred = model.predict(X_arr)

    # SHAP 值
    explainer = shap.TreeExplainer(model)
    shap_values = explainer.shap_values(X_arr)

    # 全局重要性：|SHAP| 均值
    mean_abs_shap = np.abs(shap_values).mean(axis=0)
    shap_importance = []
    for fname, mas in zip(feature_names, mean_abs_shap):
        # 区分基础特征和衍生特征
        is_base = fname in base_cols
        shap_importance.append({
            "feature": fname,
            "mean_abs_shap": round(float(mas), 6),
            "is_base": is_base,
        })
    shap_importance.sort(key=lambda x: -x["mean_abs_shap"])

    # 方向性：SHAP 均值（正=正向贡献，负=负向贡献）
    mean_shap = shap_values.mean(axis=0)
    shap_direction = {}
    for fname, ms in zip(feature_names, mean_shap):
        shap_direction[fname] = round(float(ms), 6)

    # 基础特征的 SHAP 依赖数据（用于绘制依赖图）
    base_shap_data = {}
    for bc in base_cols:
        if bc in feature_names:
            idx = feature_names.index(bc)
            base_shap_data[bc] = {
                "values": X_enhanced[bc].round(6).tolist(),
                "shap": shap_values[:, idx].round(6).tolist(),
                "mean_abs": round(float(mean_abs_shap[idx]), 6),
                "direction": round(float(mean_shap[idx]), 6),
            }

    return {
        "target": target_material,
        "n_samples": n,
        "n_features": len(feature_names),
        "cv_r2_mean": round(float(np.mean(cv_r2_scores)), 4),
        "cv_r2_std": round(float(np.std(cv_r2_scores)), 4),
        "cv_mae_mean": round(float(np.mean(cv_mae_scores)), 4),
        "train_r2": round(float(r2_score(y, y_pred)), 4),
        "train_mae": round(float(mean_absolute_error(y, y_pred)), 4),
        "y_mean": round(float(np.mean(y)), 4),
        "y_std": round(float(np.std(y)), 4),
        "y_min": round(float(np.min(y)), 4),
        "y_max": round(float(np.max(y)), 4),
        "shap_importance": shap_importance,
        "shap_direction": shap_direction,
        "base_shap_data": base_shap_data,
        "feature_names": feature_names,
        "base_features": base_cols,
    }


def fit_all_gb_shap(source_id: str, use_lag: bool = True, use_rolling: bool = True) -> Dict[str, Any]:
    """对所有出料物料拟合梯度提升+SHAP模型。"""
    fm = build_feature_matrix(source_id, use_lag=use_lag, use_rolling=use_rolling)
    if "error" in fm:
        return fm

    out_mats = fm["out_materials"]
    gb_results = {}
    for mat in out_mats:
        gb_results[mat] = fit_gb_shap_model(fm, mat, use_lag=use_lag, use_rolling=use_rolling)

    # 构建 SHAP 重要性矩阵：行=进料(基础特征)，列=出料
    in_mats = fm["in_materials"]
    shap_matrix = {}
    for im in in_mats:
        shap_matrix[im] = {}
        for om in out_mats:
            gr = gb_results.get(om, {})
            if "shap_importance" in gr:
                found = None
                for si in gr["shap_importance"]:
                    if si["feature"] == im:
                        found = si["mean_abs_shap"]
                        break
                shap_matrix[im][om] = found
            else:
                shap_matrix[im][om] = None

    return {
        "source_id": source_id,
        "granularity": fm["granularity"],
        "n_samples": fm["n_samples"],
        "date_range": fm["date_range"],
        "in_materials": in_mats,
        "out_materials": out_mats,
        "use_lag": fm.get("use_lag", False),
        "use_rolling": fm.get("use_rolling", False),
        "gb_results": gb_results,
        "shap_matrix": shap_matrix,
        "errors": [],
    }


def available_sources() -> List[Dict[str, Any]]:
    """列出有数据的物料平衡数据源。"""
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from app.data_store import load_store, source_name, source_granularity

    sources = []
    import re
    for fname in os.listdir(_DATA_DIR):
        m = re.match(r"source_(.+).pkl", fname)
        if not m:
            continue
        sid = m.group(1)
        # 只列出物料平衡数据源（排除罐表 mod_1）
        if sid.startswith("mod_"):
            continue
        store = load_store(sid)
        if store is None:
            continue
        df = store.get("long_df")
        if df is None or df.empty:
            continue
        sources.append({
            "source_id": sid,
            "name": source_name(sid),
            "granularity": source_granularity(sid),
            "row_count": len(df),
        })
    return sources
