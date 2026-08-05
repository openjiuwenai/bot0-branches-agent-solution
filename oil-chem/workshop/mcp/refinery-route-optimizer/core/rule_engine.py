# -*- coding: utf-8 -*-
"""规则引擎 —— 安全约束校验 + 因果推理。

逻辑内联自 backend/app/services/rule_engine.py,零外部依赖。
传入纯 dict(原料属性),不依赖 Pydantic 模型。
"""
import json
import os
from pathlib import Path


def _data_dir() -> Path:
    env = os.getenv("MCP_DATA_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return Path(__file__).parent.parent / "data"


def _load(filename: str):
    with open(_data_dir() / filename, encoding="utf-8") as f:
        return json.load(f)


def check_safety(feed: dict, device_id: str) -> list[str]:
    """检查安全约束,返回违规消息列表(空=通过)。

    Args:
        feed: 原料属性 dict,需含 sulfur/nitrogen/carbon_residue/density 等字段
        device_id: 装置 id,如 diesel_hydro
    """
    rules = _load("safety_rules.json")
    violations: list[str] = []

    for rule in rules:
        if rule["device_id"] != device_id:
            continue
        field = rule["field"]
        value = feed.get(field, 0)
        threshold = rule["threshold"]
        op = rule["operator"]

        violated = False
        if op == "lte" and value > threshold:
            violated = True
        elif op == "gte" and value < threshold:
            violated = True
        elif op == "lt" and value >= threshold:
            violated = True
        elif op == "gt" and value <= threshold:
            violated = True

        if violated:
            violations.append(rule["message"])

    return violations


def get_causal_reasons(feed: dict) -> list[str]:
    """根据原料特性返回因果推理说明。

    Args:
        feed: 原料属性 dict,需含 P/O/N/A/density/sulfur/nitrogen
    """
    rules = _load("causal_rules.json")
    reasons: list[str] = []

    # 构造局部变量供 condition eval 使用
    P = feed.get("P", 0)
    O = feed.get("O", 0)
    N = feed.get("N", 0)
    A = feed.get("A", 0)
    density = feed.get("density", 0)
    sulfur = feed.get("sulfur", 0)
    nitrogen = feed.get("nitrogen", 0)

    for rule in rules:
        condition = rule["condition"]
        try:
            if eval(condition):  # noqa: S307  条件来自受信配置文件
                reasons.append(rule["reason"])
        except Exception:
            pass

    # 没有触发规则时,生成默认理由
    if not reasons:
        reasons = _generate_default_reasons(feed)
    return reasons


def _generate_default_reasons(feed: dict) -> list[str]:
    """默认推荐理由(当无因果规则触发时)。逻辑同 backend 源实现。"""
    reasons: list[str] = []
    P, N, A, density, sulfur = (
        feed.get("P", 0),
        feed.get("N", 0),
        feed.get("A", 0),
        feed.get("density", 0),
        feed.get("sulfur", 0),
    )

    # 1. 烷烃含量分析
    if P >= 45:
        reasons.append(f"烷烃含量{P}%较高，柴油加氢路线饱和烃收率高，产品质量稳定")
    elif P >= 35:
        reasons.append(f"烷烃含量{P}%适中，柴油加氢和加氢裂化路线均能获得较好收率")

    # 2. 环烷烃含量分析
    if N >= 30:
        reasons.append(f"环烷烃含量{N}%有利于加氢裂化反应，轻油收率表现优秀")
    elif N >= 20:
        reasons.append(f"环烷烃含量{N}%为中等水平，加氢裂化催化剂活性正常")

    # 3. 芳烃含量分析
    if A >= 20:
        reasons.append(f"芳烃含量{A}%适中，DCC路线可产生较多丙烯等化工产品")
    elif A < 15:
        reasons.append(f"芳烃含量{A}%较低，加氢路线更为经济高效")

    # 4. 密度分析
    if density >= 0.78:
        reasons.append(f"原料密度{density}较高，分子量较大，DCC裂解效果较好")
    elif density <= 0.74:
        reasons.append(f"原料密度{density}较轻，柴油加氢路线收率稳定")

    # 5. 硫含量分析
    if sulfur <= 500:
        reasons.append(f"硫含量{sulfur}ppm较低，对加氢催化剂友好，加工成本低")
    elif sulfur <= 1500:
        reasons.append(f"硫含量{sulfur}ppm中等，加氢脱硫成本可控")
    elif sulfur <= 3000:
        reasons.append(f"硫含量{sulfur}ppm偏高，加氢路线需要更多氢耗和催化剂")

    # 6. 综合推荐:优先烷烃 + 硫含量,最多 2 条
    priority_reasons: list[str] = []
    if P >= 35:
        alkane = [r for r in reasons if "烷烃" in r]
        if alkane:
            priority_reasons.append(alkane[0])
    if sulfur <= 1500:
        sulfur_reasons = [r for r in reasons if "硫含量" in r]
        if sulfur_reasons:
            priority_reasons.append(sulfur_reasons[0])

    if not priority_reasons:
        return reasons[:2] if len(reasons) > 1 else reasons
    return priority_reasons[:2]
