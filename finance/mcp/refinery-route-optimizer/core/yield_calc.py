# -*- coding: utf-8 -*-
"""收率预测 —— 基于 PONA 线性关联模型。

逻辑内联自 backend/app/services/yield_service.py,零外部依赖。
公式: yield = base + P*cP + O*cO + N*cN + A*cA + density*cD + sulfur*cS
"""
import json
import os
from pathlib import Path
from typing import Dict


def _data_dir() -> Path:
    """配置目录:环境变量优先,默认相对本文件 ../data。"""
    env = os.getenv("MCP_DATA_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return Path(__file__).parent.parent / "data"


def load_coefficients() -> dict:
    """加载收率系数(按 device_type 分套)。"""
    with open(_data_dir() / "yield_coefficients.json", encoding="utf-8") as f:
        return json.load(f)


def predict_yields(
    P: float,
    O: float,
    N: float,
    A: float,
    density: float,
    sulfur: float,
    device_type: str,
) -> Dict[str, float]:
    """输入原料 PONA + 密度 + 硫含量 + 装置类型,返回各产品收率(% )。

    Args:
        P: 烷烃含量 %
        O: 烯烃含量 %
        N: 环烷烃含量 %
        A: 芳烃含量 %
        density: 密度 g/ml
        sulfur: 硫含量 ppm
        device_type: 装置类型(diesel_hydro / wax_hydro_crack / dcc)

    Returns:
        {产品key: 收率%},如 {"diesel": 58.2, "naphtha": 12.1, ...}
    """
    coeffs = load_coefficients()
    if device_type not in coeffs:
        raise ValueError(
            f"未知 device_type={device_type!r},可选: {list(coeffs.keys())}"
        )
    device_coeffs = coeffs[device_type]

    results: Dict[str, float] = {}
    for product, c in device_coeffs.items():
        y = (
            c["base"]
            + P * c["cP"]
            + O * c["cO"]
            + N * c["cN"]
            + A * c["cA"]
            + density * c["cD"]
            + sulfur * c["cS"]
        )
        # 收率截断到 [0, 100],保留两位小数
        results[product] = round(max(0.0, min(y, 100.0)), 2)
    return results
