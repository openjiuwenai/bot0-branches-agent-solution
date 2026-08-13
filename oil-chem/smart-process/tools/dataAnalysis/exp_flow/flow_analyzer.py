# -*- coding: utf-8 -*-
"""罐-物料-装置关联匹配分析模块。"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pandas as pd
from typing import Dict, Any, List, Optional
from app import data_store


def _norm(s: str) -> str:
    if s is None:
        return ""
    s = str(s).replace(" ", "").replace("　", "").strip()
    s = s.replace("（", "(").replace("）", ")")
    return s.lower()


SYNONYM_MAP = {
    "HC原料": ["直馏柴油", "罐区柴油", "精制柴油", "DCC柴油", "蜡油加氢柴油", "蜡油加氢石脑油", "裂柴加氢石脑油", "3#直馏石脑油", "裂解石脑油C5", "蜡加柴油", "氢气", "新氢"],
    "DCC原料": ["DCC柴油", "蜡油加氢柴油", "蜡油加氢石脑油", "蜡加柴油"],
    "航煤": ["航煤"],
    "车用柴油": ["柴油", "分馏塔底柴油"],
    "石脑油（汽油料）": ["重石脑油", "轻石脑油"],
    "石脑油（乙烯料）": ["重石脑油", "轻石脑油"],
    "石脑油（互供料）": ["重石脑油", "轻石脑油"],
    "精制石脑油": ["重石脑油", "轻石脑油"],
    "裂解石脑油": ["裂解石脑油C5"],
    "液化石油气": ["粗液化气"],
    "液化石油气（工业用）": ["粗液化气"],
    "液化石油气（混合C4）": ["粗液化气"],
    "饱和LPG": ["粗液化气"],
    "气分液化气": ["粗液化气"],
    "污油": ["污油", "清罐污油", "轻污油", "重污油"],
    "轻质燃料油": ["轻质燃料油"],
    "燃料油F-D1": ["轻质燃料油"],
    "燃料油DMB": ["轻质燃料油"],
    "250#燃料油": ["轻质燃料油"],
    "380#燃料油": ["轻质燃料油"],
}


def load_material_balance(source_id):
    store = data_store.load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return None
    df = store["long_df"]
    time_col = store.get("time_col")
    if not time_col or time_col not in df.columns:
        return None

    io_col = None
    for c in df.columns:
        if c == time_col:
            continue
        vals = set(df[c].dropna().astype(str).str.strip().unique())
        if vals and vals <= {"进", "出"}:
            io_col = c
            break

    name_col = None
    for c in df.columns:
        if c == time_col or c == io_col:
            continue
        cn = _norm(c)
        if "物料" in cn or "material" in cn or "油品" in cn or "名称" in cn:
            name_col = c
            break
    if name_col is None:
        for c in df.columns:
            if c == time_col or c == io_col:
                continue
            cn = _norm(c)
            if "node_alias" in cn or "alias" in cn or "display" in cn or "侧线" in cn:
                name_col = c
                break
    if name_col is None:
        for c in df.columns:
            if c in (time_col, io_col):
                continue
            if df[c].dtype == object:
                name_col = c
                break
    if name_col is None:
        return None

    value_cols = store.get("value_cols") or []
    summary_col = None
    for vc in value_cols:
        if "bal" in str(vc).lower() and vc in df.columns:
            summary_col = vc
            break
    if summary_col is None and value_cols:
        summary_col = value_cols[0] if value_cols[0] in df.columns else None
    if summary_col is None:
        for c in df.columns:
            if c in (time_col, io_col, name_col):
                continue
            if pd.api.types.is_numeric_dtype(df[c]):
                summary_col = c
                break
    if summary_col is None:
        return None

    # 清理物料名：去除装置前缀和流向后缀
    unit_prefixes = ["2#加裂装置", "2#加氢裂化", "DCC装置", "DCC催化裂解"]
    flow_suffixes = ["至罐区", "来自罐区", "出装置", "至三期重整", "至五期重整", "自罐区", "至罐", "至重整", "至装置"]
    def _clean_name(v):
        if pd.isna(v):
            return v
        sv = str(v)
        for pfx in unit_prefixes:
            if sv.startswith(pfx):
                sv = sv[len(pfx):]
                break
        for sfx in flow_suffixes:
            if sv.endswith(sfx):
                sv = sv[:len(sv) - len(sfx)]
                break
        return sv
    work = df[[time_col, name_col, summary_col]].copy()
    work[name_col] = work[name_col].apply(_clean_name)
    if io_col:
        work[io_col] = df[io_col]
    work[summary_col] = pd.to_numeric(work[summary_col], errors="coerce")
    work = work.dropna(subset=[time_col, summary_col, name_col])

    materials_in = []
    materials_out = []

    if io_col and io_col in work.columns:
        for name in work[name_col].astype(str).unique():
            sub = work[work[name_col] == name]
            dirs = sub[io_col].astype(str).str.strip().unique()
            for d in dirs:
                total = sub[sub[io_col] == d][summary_col].sum()
                entry = {"name": str(name), "total": round(float(total), 2)}
                if d == "进":
                    materials_in.append(entry)
                else:
                    materials_out.append(entry)
    else:
        for name in work[name_col].astype(str).unique():
            total = work[work[name_col] == name][summary_col].sum()
            materials_in.append({"name": str(name), "total": round(float(total), 2)})

    materials_in.sort(key=lambda x: -x["total"])
    materials_out.sort(key=lambda x: -x["total"])

    unit_name = source_id
    try:
        unit_name = data_store.module_name(source_id)
    except Exception:
        pass

    return {
        "source_id": source_id,
        "unit_name": unit_name,
        "materials_in": materials_in,
        "materials_out": materials_out,
        "time_col": time_col,
        "row_count": len(work),
    }


def load_tank_data(source_id):
    store = data_store.load_store(source_id)
    if not store or store.get("long_df") is None or store["long_df"].empty:
        return None
    df = store["long_df"]

    tank_col = None
    product_col = None
    for c in df.columns:
        cn = _norm(c)
        if "罐号" in cn or cn == "罐":
            tank_col = c
        if "油品" in cn or "物料" in cn or "产品" in cn:
            product_col = c
    if not tank_col or not product_col:
        return None

    tank_material = df.groupby([tank_col, product_col]).size().reset_index(name="count")
    tank_material = tank_material.sort_values("count", ascending=False)

    material_tank_map = {}
    for _, row in tank_material.iterrows():
        mat = str(row[product_col])
        tk = str(row[tank_col])
        if mat not in material_tank_map:
            material_tank_map[mat] = []
        if tk not in material_tank_map[mat]:
            material_tank_map[mat].append(tk)

    materials = []
    for mat in sorted(material_tank_map.keys()):
        materials.append({"name": mat, "tank_count": len(material_tank_map[mat]),
                          "tanks": material_tank_map[mat]})

    return {
        "source_id": source_id,
        "tank_col": tank_col,
        "product_col": product_col,
        "materials": materials,
        "material_tank_map": material_tank_map,
        "row_count": len(df),
    }


def match_materials(tank_mats, mb_mats):
    matches = []
    mb_norm = {_norm(m): m for m in mb_mats}

    for tm in tank_mats:
        tn = _norm(tm)
        best = None
        best_score = 0

        if tn in mb_norm:
            best = mb_norm[tn]
            best_score = 1.0

        if not best:
            for mn, orig in mb_norm.items():
                if len(mn) >= 2 and (mn in tn or tn in mn):
                    score = min(len(mn), len(tn)) / max(len(mn), len(tn))
                    if score > best_score:
                        best_score = score
                        best = orig

        if not best:
            for syn_key, syn_vals in SYNONYM_MAP.items():
                if tn == _norm(syn_key) or _norm(syn_key) in tn:
                    for sv in syn_vals:
                        if sv in mb_norm.values():
                            best = sv
                            best_score = 0.8
                            break
                    if best:
                        break

        matches.append({
            "tank_material": tm,
            "matched_mb_material": best,
            "score": round(best_score, 2),
            "matched": best is not None,
        })

    return matches


def build_flow_graph():
    mb_sources = []
    for sid in ["2_hydrocracking", "2_hydrocracking_hourly", "dcc", "dcc_hourly"]:
        mb = load_material_balance(sid)
        if mb:
            mb_sources.append(mb)

    tank = load_tank_data("mod_1")
    if not tank:
        return {"has_data": False, "errors": ["未找到罐表数据"]}

    all_mb_mats = set()
    for mb in mb_sources:
        for m in mb["materials_in"]:
            all_mb_mats.add(m["name"])
        for m in mb["materials_out"]:
            all_mb_mats.add(m["name"])

    tank_mats = [m["name"] for m in tank["materials"]]
    matches = match_materials(tank_mats, list(all_mb_mats))

    nodes = []
    edges = []
    node_id_map = {}

    def get_node_id(label, node_type):
        key = node_type + ":" + label
        if key not in node_id_map:
            nid = "n" + str(len(node_id_map))
            node_id_map[key] = nid
            node = {"id": nid, "label": label, "type": node_type}
            nodes.append(node)
        return node_id_map[key]

    for m in tank["materials"]:
        tid = get_node_id(m["name"], "tank")

    for mb in mb_sources:
        unit_id = get_node_id(mb["unit_name"], "unit")

        for m in mb["materials_in"]:
            mat_name = m["name"]
            matched_tank = None
            for match in matches:
                if match["matched_mb_material"] == mat_name:
                    matched_tank = match["tank_material"]
                    break
            if matched_tank:
                src_id = get_node_id(matched_tank, "tank")
            else:
                src_id = get_node_id(mat_name, "external_in")
            edges.append({
                "source": src_id, "target": unit_id,
                "label": mat_name, "value": m["total"], "type": "feed"
            })

        for m in mb["materials_out"]:
            mat_name = m["name"]
            matched_tank = None
            for match in matches:
                if match["matched_mb_material"] == mat_name:
                    matched_tank = match["tank_material"]
                    break
            if matched_tank:
                tgt_id = get_node_id(matched_tank, "tank")
            else:
                tgt_id = get_node_id(mat_name, "external_out")
            edges.append({
                "source": unit_id, "target": tgt_id,
                "label": mat_name, "value": m["total"], "type": "product"
            })

    matched_count = sum(1 for m in matches if m["matched"])

    return {
        "has_data": True,
        "nodes": nodes,
        "edges": edges,
        "matches": matches,
        "matched_count": matched_count,
        "total_tank_materials": len(tank_mats),
        "mb_sources": [{"source_id": mb["source_id"], "unit_name": mb["unit_name"],
                        "in_count": len(mb["materials_in"]), "out_count": len(mb["materials_out"])}
                       for mb in mb_sources],
        "tank_count": len(tank["material_tank_map"]),
        "tank_material_count": len(tank["materials"]),
        "errors": [],
    }
