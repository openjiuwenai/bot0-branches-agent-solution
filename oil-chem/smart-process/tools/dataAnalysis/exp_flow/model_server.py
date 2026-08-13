# -*- coding: utf-8 -*-
"""收率预测模型实验服务。独立端口 8767。"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from fastapi import FastAPI
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from . import yield_model

app = FastAPI(title="收率预测模型(实验)")

_static_dir = os.path.join(os.path.dirname(__file__), "static")
_templates_dir = os.path.join(os.path.dirname(__file__), "templates")
os.makedirs(_static_dir, exist_ok=True)
os.makedirs(_templates_dir, exist_ok=True)
app.mount("/static", StaticFiles(directory=_static_dir), name="static")


@app.get("/", response_class=HTMLResponse)
async def index():
    with open(os.path.join(_templates_dir, "model.html"), encoding="utf-8") as f:
        return f.read()


@app.get("/api/sources")
async def api_sources():
    return JSONResponse(yield_model.available_sources())


@app.get("/api/fit")
async def api_fit(source_id: str, use_lag: bool = True, use_rolling: bool = False):
    result = yield_model.fit_all_models(source_id, use_lag=use_lag, use_rolling=use_rolling)
    # 序列化：去掉非JSON兼容的对象
    if "error" in result:
        return JSONResponse(result)
    return JSONResponse({
        "source_id": result["source_id"],
        "granularity": result["granularity"],
        "n_samples": result["n_samples"],
        "date_range": result["date_range"],
        "in_materials": result["in_materials"],
        "out_materials": result["out_materials"],
        "coef_matrix": result["coef_matrix"],
        "ridge_results": result["ridge_results"],
        "lasso_results": result["lasso_results"],
        "feature_info": result["feature_info"],
        "use_rolling": use_rolling,
        "errors": result.get("errors", []),
    })


@app.get("/api/feature-stats")
async def api_feature_stats(source_id: str):
    """返回进料占比和出料收率的统计信息，用于诊断模型效果。"""
    fm = yield_model.build_feature_matrix(source_id, use_lag=False)
    if "error" in fm:
        return JSONResponse(fm)
    X = fm["X_ratio"]
    Y = fm["Y_yield"]
    in_stats = []
    for col in X.columns:
        s = X[col]
        cv = float(s.std() / s.mean() * 100) if s.mean() != 0 else 0
        in_stats.append({
            "name": col, "mean": round(float(s.mean()), 4),
            "std": round(float(s.std()), 4), "min": round(float(s.min()), 4),
            "max": round(float(s.max()), 4), "cv": round(cv, 1),
        })
    out_stats = []
    for col in Y.columns:
        s = Y[col]
        cv = float(s.std() / s.mean() * 100) if s.mean() != 0 else 0
        out_stats.append({
            "name": col, "mean": round(float(s.mean()), 2),
            "std": round(float(s.std()), 2), "min": round(float(s.min()), 2),
            "max": round(float(s.max()), 2), "cv": round(cv, 1),
        })
    return JSONResponse({
        "source_id": source_id,
        "granularity": fm["granularity"],
        "n_samples": fm["n_samples"],
        "in_stats": in_stats,
        "out_stats": out_stats,
    })

@app.get("/api/fit-gb")
async def api_fit_gb(source_id: str, use_lag: bool = True, use_rolling: bool = True):
    """梯度提升树 + SHAP 分析。"""
    result = yield_model.fit_all_gb_shap(source_id, use_lag=use_lag, use_rolling=use_rolling)
    if "error" in result:
        return JSONResponse(result)
    return JSONResponse({
        "source_id": result["source_id"],
        "granularity": result["granularity"],
        "n_samples": result["n_samples"],
        "date_range": result["date_range"],
        "in_materials": result["in_materials"],
        "out_materials": result["out_materials"],
        "use_lag": result.get("use_lag", False),
        "use_rolling": result.get("use_rolling", False),
        "gb_results": result["gb_results"],
        "shap_matrix": result["shap_matrix"],
        "errors": result.get("errors", []),
    })


@app.get("/api/compare")
async def api_compare(source_id: str, use_lag: bool = True, use_rolling: bool = True):
    """对比岭回归 vs 梯度提升的预测效果。"""
    ridge = yield_model.fit_all_models(source_id, use_lag=use_lag, use_rolling=use_rolling)
    gb = yield_model.fit_all_gb_shap(source_id, use_lag=use_lag, use_rolling=use_rolling)
    if "error" in ridge:
        return JSONResponse(ridge)
    if "error" in gb:
        return JSONResponse(gb)

    comparison = []
    for om in ridge["out_materials"]:
        rr = ridge["ridge_results"].get(om, {})
        gr = gb["gb_results"].get(om, {})
        if "error" in rr or "error" in gr:
            continue
        comparison.append({
            "target": om,
            "ridge_cv_r2": rr.get("cv_r2_mean"),
            "ridge_train_r2": rr.get("train_r2"),
            "ridge_cv_mae": rr.get("cv_mae_mean"),
            "gb_cv_r2": gr.get("cv_r2_mean"),
            "gb_train_r2": gr.get("train_r2"),
            "gb_cv_mae": gr.get("cv_mae_mean"),
            "y_mean": rr.get("y_mean"),
            "y_std": rr.get("y_std"),
        })
    return JSONResponse({
        "source_id": source_id,
        "n_samples": ridge["n_samples"],
        "granularity": ridge["granularity"],
        "comparison": comparison,
    })
