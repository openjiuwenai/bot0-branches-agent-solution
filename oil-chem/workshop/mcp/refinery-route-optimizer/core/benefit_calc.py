# -*- coding: utf-8 -*-
"""效益计算服务 —— 三路线对比。

逻辑内联自 backend/app/services/benefit_service.py,零外部依赖。
返回纯 dict(对齐 backend RouteCompareResult 字段),便于 MCP 序列化。
"""
import json
import os
from pathlib import Path
from typing import Dict, List

from .yield_calc import predict_yields
from .rule_engine import check_safety, get_causal_reasons


def _data_dir() -> Path:
    env = os.getenv("MCP_DATA_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return Path(__file__).parent.parent / "data"


def _load(filename: str):
    with open(_data_dir() / filename, encoding="utf-8") as f:
        return json.load(f)


def _build_feed_dict(
    P: float,
    O: float,
    N: float,
    A: float,
    density: float,
    sulfur: float,
    nitrogen: float = 0,
    carbon_residue: float = 0,
    feed_rate: float = 3500,
) -> dict:
    """构造原料属性 dict(供 rule_engine 使用)。"""
    return {
        "P": P, "O": O, "N": N, "A": A,
        "density": density, "sulfur": sulfur,
        "nitrogen": nitrogen, "carbon_residue": carbon_residue,
        "feed_rate": feed_rate,
    }


def _validate_pona(P: float, O: float, N: float, A: float) -> None:
    """PONA 总和校验(95%~105%)。"""
    total = P + O + N + A
    if not (95.0 <= total <= 105.0):
        raise ValueError(f"PONA 总和应约为100%，当前为 {total:.1f}%")


def calculate_route(
    feed: dict,
    device: dict,
    route_label: str,
    products_override: dict | None = None,
) -> dict:
    """计算单条路线的效益。

    Args:
        feed: 原料属性 dict
        device: 装置配置 dict(来自 devices.json)
        route_label: 路线标签,如 "路线A"
        products_override: 可选,产品价格覆盖
    """
    products_config = products_override if products_override is not None else _load("products.json")
    cost_params = _load("cost_params.json")

    # 1. 预测收率
    yields = predict_yields(
        P=feed["P"], O=feed["O"], N=feed["N"], A=feed["A"],
        density=feed["density"], sulfur=feed["sulfur"],
        device_type=device["device_type"],
    )

    # 2. 各产品价值
    product_list: List[dict] = []
    total_value = 0.0
    for prod_key, yield_pct in yields.items():
        pc = products_config.get(prod_key, {})
        price = pc.get("transfer_price", pc.get("price", 0))
        value = round(yield_pct * price / 100, 2)
        total_value += value
        product_list.append({
            "product_key": prod_key,
            "product_name": pc.get("name", prod_key),
            "yield_pct": yield_pct,
            "price": price,
            "value_per_ton": value,
        })

    # 3. 加工成本
    cost = cost_params[device["device_type"]]["total"]
    feedstock_cost = cost_params["feedstock_base_price"]

    # 4. 吨油毛利 + 日效益
    margin = round(total_value - feedstock_cost - cost, 2)
    daily = round(margin * feed.get("feed_rate", 3500), 2)

    # 5. 安全规则校验
    violations = check_safety(feed, device["device_id"])

    # 6. 因果推理
    causal = get_causal_reasons(feed)

    return {
        "device_id": device["device_id"],
        "device_name": device["name"],
        "ontology_device_id": device.get("ontology_device_id", ""),
        "route_label": route_label,
        "products": product_list,
        "total_product_value": round(total_value, 2),
        "processing_cost": cost,
        "feedstock_cost": feedstock_cost,
        "gross_margin": margin,
        "daily_benefit": daily,
        "is_recommended": False,
        "recommendation_reason": "",
        "safety_violations": violations,
        "causal_reasons": causal,
    }


def compare_routes(
    P: float,
    O: float,
    N: float,
    A: float,
    density: float,
    sulfur: float,
    nitrogen: float = 0,
    carbon_residue: float = 0,
    feed_rate: float = 3500,
    batch_id: str = "",
    products_override: dict | None = None,
) -> dict:
    """三路线效益对比 —— 项目核心能力。

    输入原料 PONA 等参数,对柴油加氢 / 蜡油加氢裂化 / DCC 三条路线
    分别计算收率、效益、安全合规,并给出最优推荐。

    Args:
        P: 烷烃含量 %  (0~100)
        O: 烯烃含量 %  (0~100)
        N: 环烷烃含量 % (0~100)
        A: 芳烃含量 %  (0~100,PONA 总和应约 100%)
        density: 密度 g/ml (0.6~1.0)
        sulfur: 硫含量 ppm (>=0)
        nitrogen: 氮含量 ppm (默认 0)
        carbon_residue: 残炭 % (默认 0)
        feed_rate: 进料量 吨/日 (默认 3500)
        batch_id: 批次编号(可选,仅展示用)

    Returns:
        {
          "batch_id": ...,
          "feed_rate": ...,
          "routes": [3 条路线详情],
          "best_route": "device_id",
          "safety_check_passed": bool,
          "safety_violations": [所有违规汇总],
        }
    """
    _validate_pona(P, O, N, A)

    feed = _build_feed_dict(P, O, N, A, density, sulfur, nitrogen, carbon_residue, feed_rate)
    devices = _load("devices.json")
    labels = ["路线A", "路线B", "路线C"]

    routes: List[dict] = []
    all_violations: List[str] = []
    for i, device in enumerate(devices):
        route = calculate_route(feed, device, labels[i], products_override=products_override)
        routes.append(route)
        all_violations.extend(route["safety_violations"])

    # 最优选择:有严重安全违规的路线降权
    valid_routes = [r for r in routes if not r["safety_violations"]]
    candidate = valid_routes if valid_routes else routes
    best = max(candidate, key=lambda r: r["gross_margin"])

    for r in routes:
        r["is_recommended"] = (r["device_id"] == best["device_id"])
        if r["is_recommended"]:
            r["recommendation_reason"] = (
                f"吨油毛利最高，达 {r['gross_margin']:.0f} 元/吨，"
                f"日效益 {r['daily_benefit'] / 10000:.1f} 万元"
            )

    return {
        "batch_id": batch_id,
        "feed_rate": feed_rate,
        "routes": routes,
        "best_route": best["device_id"],
        "safety_check_passed": len(all_violations) == 0,
        "safety_violations": all_violations,
    }
