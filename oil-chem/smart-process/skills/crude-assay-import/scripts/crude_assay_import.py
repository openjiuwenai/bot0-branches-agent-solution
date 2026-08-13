#!/usr/bin/env python3
"""
原油评价报告解析脚本（自包含，零 backend 依赖）

用法:
    python crude_assay_import.py <file.xls> [options]

选项:
    --crude-name NAME        指定原油名（默认从封面提取）
    --sample-date YYYYMMDD   指定采样日期（默认从封面提取）
    --sample-point POINT     指定采样点（默认从封面提取）
    --json OUTPUT.json       输出结构化 JSON 到文件
    --import-pg              可选：导入 PostgreSQL（需 DATABASE_URL 环境变量）
    --import-chroma          可选：导入 ChromaDB（需 chromadb 已安装）

依赖:
    pip install xlrd          # 必需，解析 .xls
    pip install psycopg2-binary  # 仅 --import-pg 时需要
    pip install chromadb      # 仅 --import-chroma 时需要

解析逻辑源自慧炼项目 data_processor.py，详见 SKILL.md。
"""
import os
import sys
import json
import re
import argparse

# ---------------------------------------------------------------------------
# 侧线切割温度定义（℃）— 侧线收率计算的唯一依据
# ---------------------------------------------------------------------------
SIDELINE_CUTS = {
    "石脑油": {"start": 0,    "end": 180,  "label": "初馏点~180℃"},
    "常一线": {"start": 180,  "end": 220,  "label": "180℃~220℃"},
    "常二线": {"start": 220,  "end": 300,  "label": "220℃~300℃"},
    "常三线": {"start": 300,  "end": 370,  "label": "300℃~370℃"},
    "减一线": {"start": 370,  "end": 395,  "label": "370℃~395℃"},
    "减二线": {"start": 395,  "end": 450,  "label": "395℃~450℃"},
    "减三线": {"start": 450,  "end": 500,  "label": "450℃~500℃"},
    "减四线": {"start": 500,  "end": 540,  "label": "500℃~540℃"},
    "渣油":   {"start": 540,  "end": 9999, "label": ">540℃"},
}

# 侧线别名映射（问答系统归一用）
SIDELINE_ALIASES = {
    "石脑油": ["石脑油", "轻石脑油", "重石脑油", "naphtha"],
    "常一线": ["常一线", "常一", "常压一线", "航煤", "喷气燃料"],
    "常二线": ["常二线", "常二", "常压二线", "煤油"],
    "常三线": ["常三线", "常三", "常压三线", "柴油"],
    "减一线": ["减一线", "减一", "减压一线", "vgo1"],
    "减二线": ["减二线", "减二", "减压二线", "vgo2"],
    "减三线": ["减三线", "减三", "减压三线", "vgo3"],
    "减四线": ["减四线", "减四", "减压四线"],
    "渣油":   ["渣油", "常压渣油", "减压渣油", "残渣", "residue"],
}

# 章节号映射
SECTION_INDEX_MAP = {
    "1": 1, "2": 3, "3": 5, "4": 7, "5": 9, "6": 11, "7": 13, "8": 15,
}


# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------

def parse_temp_range(boiling_range):
    """解析沸点范围字符串 → (start, end) 整数元组，无法解析返回 None"""
    if not boiling_range:
        return None
    m = re.match(r'<?\s*(\d+)\s*[～~]\s*(\d+)', boiling_range)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.match(r'<\s*(\d+)', boiling_range)
    if m:
        return 0, int(m.group(1))
    m = re.match(r'>\s*(\d+)', boiling_range)
    if m:
        return int(m.group(1)), 9999
    return None


def _safe_float(sh, r, c):
    if c is None:
        return None
    try:
        v = sh.cell(r, c).value
        if v == "" or v is None or str(v).strip() in ["", "-", "--"]:
            return None
        return float(v)
    except Exception:
        return None


def _safe_str(sh, r, c):
    try:
        v = sh.cell(r, c).value
        if v is None or str(v).strip() in ["", "-", "--"]:
            return None
        return str(v).strip()
    except Exception:
        return None


def _parse_date_to_yyyymmdd(date_str):
    """将各种日期格式转为 YYYYMMDD"""
    m = re.match(r'(\d{4})[/\-年](\d{1,2})[/\-月](\d{1,2})', date_str)
    if m:
        y, mo, d = m.group(1), int(m.group(2)), int(m.group(3))
        return f"{y}{mo:02d}{d:02d}"
    m = re.match(r'^(\d{8})$', date_str.replace(' ', ''))
    if m:
        return m.group(1)
    return ""


# ---------------------------------------------------------------------------
# Sheet 解析器
# ---------------------------------------------------------------------------

def parse_cover_sheet(wb):
    """解析封面 sheet → {"crude_name", "sample_point", "sample_date"}"""
    result = {"crude_name": "", "sample_point": "", "sample_date": ""}
    try:
        sh = wb.sheet_by_name("封面")
    except Exception:
        try:
            sh = wb.sheet_by_index(0)
        except Exception:
            return result

    for r in range(min(80, sh.nrows)):
        row_texts = {}
        for c in range(min(sh.ncols, 15)):
            v = sh.cell(r, c).value
            if v and str(v).strip():
                row_texts[c] = str(v).strip()

        # 找"原油名称"
        label_col = None
        for c, text in row_texts.items():
            if "原油名称" in text:
                label_col = c
                break

        if label_col is not None:
            raw = ""
            if sh.ncols > 2:
                v = sh.cell(r, 2).value
                raw = str(v).strip() if v and str(v).strip() else ""
            if not raw:
                for c in range(label_col + 1, min(sh.ncols, label_col + 5)):
                    v = sh.cell(r, c).value
                    if v and str(v).strip():
                        raw = str(v).strip()
                        break
            if raw:
                m = re.match(r'^(.+?)[（(](.+?)[)）]\s*$', raw)
                if m:
                    result["crude_name"] = m.group(1).strip()
                    result["sample_point"] = m.group(2).strip()
                else:
                    result["crude_name"] = raw
            continue

        # 找"采样日期"
        for c, text in row_texts.items():
            if "采样日期" in text:
                date_raw = ""
                cell = None
                if sh.ncols > 2:
                    cell = sh.cell(r, 2)
                    v = cell.value
                    date_raw = str(v).strip() if v and str(v).strip() else ""
                if not date_raw:
                    for dc in range(c + 1, min(sh.ncols, c + 5)):
                        cell = sh.cell(r, dc)
                        v = cell.value
                        if v and str(v).strip():
                            date_raw = str(v).strip()
                            break
                if date_raw:
                    if cell is not None and cell.ctype == 3:
                        try:
                            import xlrd as _xlrd
                            dt = _xlrd.xldate_as_datetime(cell.value, wb.datemode)
                            result["sample_date"] = dt.strftime("%Y%m%d")
                        except Exception:
                            result["sample_date"] = _parse_date_to_yyyymmdd(date_raw)
                    else:
                        result["sample_date"] = _parse_date_to_yyyymmdd(date_raw)
                break

    return result


def parse_distillation_sheet(wb, crude_name):
    """解析实沸点 sheet → list[dict]

    两种格式方案：
      方案一：硬编码列号（标准格式，行3起为数据）
      方案二：自动检测表头（兼容非标准格式）
    """
    try:
        sh = wb.sheet_by_name("实沸点")
    except Exception:
        try:
            sh = wb.sheet_by_name("实沸点蒸馏")
        except Exception:
            return []

    rows = []

    # 方案一：硬编码列号
    if sh.nrows > 3:
        first_val = str(sh.cell(3, 0).value).strip()
        if first_val and (first_val[0].isdigit() or first_val[0] in '<>'):
            for r in range(3, sh.nrows):
                boiling_range = sh.cell(r, 0).value
                if not boiling_range or str(boiling_range).strip() in ["", "-", "损失"]:
                    continue
                row = {
                    "crude_name": crude_name,
                    "boiling_range": str(boiling_range).strip(),
                    "yield_per_mass": _safe_float(sh, r, 1),
                    "yield_total_mass": _safe_float(sh, r, 2),
                    "yield_per_vol": _safe_float(sh, r, 3),
                    "yield_total_vol": _safe_float(sh, r, 4),
                    "density_20": _safe_float(sh, r, 5),
                }
                if sh.ncols > 6:
                    row["pour_point"] = _safe_float(sh, r, 6)
                if sh.ncols > 7:
                    row["acidity"] = _safe_float(sh, r, 7)
                if sh.ncols > 8:
                    row["acid_value"] = _safe_float(sh, r, 8)
                if sh.ncols > 9:
                    row["sulfur"] = _safe_float(sh, r, 9)
                if sh.ncols > 10:
                    row["nitrogen"] = _safe_float(sh, r, 10)
                rows.append(row)
            return rows

    # 方案二：自动检测表头
    header_row = None
    for r in range(min(10, sh.nrows)):
        first_cell = str(sh.cell(r, 0).value).strip()
        if "沸点范围" in first_cell or "馏分" in first_cell or "温度" in first_cell:
            header_row = r
            break

    if header_row is None:
        return []

    col_map = {}
    sub_row = header_row + 1 if header_row + 1 < sh.nrows else None
    for c in range(sh.ncols):
        h = str(sh.cell(header_row, c).value).strip()
        if sub_row is not None:
            h2 = str(sh.cell(sub_row, c).value).strip()
            full_h = h + h2 if h2 and h2 != h else h
        else:
            full_h = h
        if not full_h:
            continue
        if "沸点" in full_h:
            col_map["boiling_range"] = c
        elif "m/m" in full_h or ("占原油" in full_h and ("质量" in full_h or "每" in full_h)):
            if "每" in full_h or "馏" in full_h:
                col_map["yield_mass"] = c
            elif "总" in full_h:
                col_map["yield_total_mass"] = c
        elif "V/V" in full_h or ("占原油" in full_h and "体积" in full_h):
            if "每" in full_h or "馏" in full_h:
                col_map["yield_vol"] = c
            elif "总" in full_h:
                col_map["yield_total_vol"] = c
        elif "收率" in full_h and "总" in full_h:
            col_map["yield_total_mass" if "质量" in full_h or "m/m" in full_h else "yield_total_vol"] = c
        elif "收率" in full_h and ("每" in full_h or "馏" in full_h):
            col_map["yield_mass" if "质量" in full_h or "m/m" in full_h else "yield_vol"] = c
        elif "密度" in full_h:
            col_map["density"] = c
        elif "硫" in full_h:
            col_map["sulfur"] = c
        elif "氮" in full_h:
            col_map["nitrogen"] = c
        elif "酸值" in full_h:
            col_map["acid_value"] = c
        elif "粘度" in full_h or "黏度" in full_h:
            if "20" in full_h:
                col_map["viscosity_20"] = c
            elif "50" in full_h:
                col_map["viscosity_50"] = c
            elif "80" in full_h:
                col_map["viscosity_80"] = c
            elif "100" in full_h:
                col_map["viscosity_100"] = c

    if "boiling_range" not in col_map:
        return []

    for r in range(header_row + (2 if sub_row else 1), sh.nrows):
        br = str(sh.cell(r, col_map["boiling_range"]).value).strip()
        if not br or br in ["-", ""] or br == "损失":
            continue
        row = {"crude_name": crude_name, "boiling_range": br}
        row["yield_per_mass"] = _safe_float(sh, r, col_map.get("yield_mass"))
        row["yield_total_mass"] = _safe_float(sh, r, col_map.get("yield_total_mass"))
        row["yield_per_vol"] = _safe_float(sh, r, col_map.get("yield_vol"))
        row["yield_total_vol"] = _safe_float(sh, r, col_map.get("yield_total_vol"))
        row["density_20"] = _safe_float(sh, r, col_map.get("density"))
        if "sulfur" in col_map:
            row["sulfur"] = _safe_float(sh, r, col_map["sulfur"])
        if "nitrogen" in col_map:
            row["nitrogen"] = _safe_float(sh, r, col_map["nitrogen"])
        if "acid_value" in col_map:
            row["acid_value"] = _safe_float(sh, r, col_map["acid_value"])
        rows.append(row)

    return rows


def parse_crude_properties_sheet(wb, crude_name):
    """解析原油性质 sheet → (props_dict, props_list)"""
    try:
        sh = wb.sheet_by_name("原油性质")
    except Exception:
        try:
            sh = wb.sheet_by_name("一般性质")
        except Exception:
            return {}, []

    props_dict = {}
    props_list = []
    current_section = None

    for r in range(2, sh.nrows):
        name = sh.cell(r, 0).value
        unit = sh.cell(r, 1).value
        val = sh.cell(r, 3).value

        if not name:
            continue
        raw_name = str(name)
        name_str = raw_name.strip()
        if not name_str or name_str in ["分析项目", "表1"] or name_str.startswith("表"):
            continue

        name_str = name_str.replace("（", "(").replace("）", ")")
        name_str = name_str.replace("　", "").strip()

        has_leading_space = raw_name != raw_name.lstrip()
        is_temp_item = bool(re.match(r'^[\d.]+℃', name_str))

        val_cell = sh.cell(r, 3)
        if val_cell.ctype == 5:
            val_str = "/"
        else:
            val_str = str(val).strip() if val is not None else ""

        if val_str in ["", "——"]:
            if not has_leading_space and not is_temp_item:
                current_section = name_str
                continue

        unit_str = ""
        if unit is not None:
            unit_str = str(unit).strip()
            if unit_str in ["/", "-", "--", "——"]:
                unit_str = ""
            unit_str = unit_str.replace("（", "(").replace("）", ")")

        if current_section and (has_leading_space or is_temp_item):
            display_name = f"{current_section} {name_str}"
        elif current_section and not has_leading_space and not is_temp_item:
            display_name = name_str
            current_section = None
        else:
            display_name = name_str

        if val_str in ["", "/", "-", "--"]:
            props_dict[name_str] = None
            val_for_list = None
        else:
            try:
                val_float = float(val)
                props_dict[name_str] = val_float
                val_for_list = val_float
            except (ValueError, TypeError):
                props_dict[name_str] = val_str
                val_for_list = val_str

        props_list.append({
            "name": display_name,
            "unit": unit_str,
            "value": val_for_list,
        })

    return props_dict, props_list


def parse_residue_sheet(wb, crude_name):
    """解析渣油 sheet → {"residues": [...], "residue_list": [...]}"""
    sh = wb.sheet_by_name("渣油")
    residues = []
    residue_list = []

    # 旧格式
    for col_idx, label in [(3, ">350℃"), (4, ">540℃")]:
        props = {}
        current_section = None
        for r in range(2, sh.nrows):
            name = sh.cell(r, 0).value
            val = sh.cell(r, col_idx).value
            if not name:
                continue
            raw_name = str(name)
            name_str = raw_name.strip().replace("（", "(").replace("）", ")")
            if not name_str or name_str.startswith("表"):
                continue

            val_cell_r = sh.cell(r, col_idx)
            other_cell_r = sh.cell(r, 4 if col_idx == 3 else 3)
            val_str = "/" if val_cell_r.ctype == 5 else (str(val).strip() if val is not None else "")
            other_val = other_cell_r.value
            other_str = "/" if other_cell_r.ctype == 5 else (str(other_val).strip() if other_val is not None else "")
            if val_str in ["", "——"] and other_str in ["", "——"]:
                current_section = name_str
                continue

            key = name_str
            if current_section and raw_name != raw_name.lstrip():
                key = f"{current_section} {name_str}"
            if val_str in ["", "——", "/", "-", "--"]:
                props[key] = None
            else:
                try:
                    props[key] = float(val)
                except (ValueError, TypeError):
                    props[key] = str(val).strip()
        residues.append({"temperature_range": label, "properties": props})

    # 新格式
    current_section = None
    for r in range(2, sh.nrows):
        name = sh.cell(r, 0).value
        if not name:
            continue
        raw_name = str(name)
        name_str = raw_name.strip().replace("（", "(").replace("）", ")")
        if not name_str or name_str.startswith("表"):
            continue

        unit = sh.cell(r, 1).value or ""
        val350_cell = sh.cell(r, 3)
        val540_cell = sh.cell(r, 4)
        val_350 = val350_cell.value
        val_540 = val540_cell.value
        recommendation = sh.cell(r, 5).value if sh.ncols > 5 else None

        val350_str = "/" if val350_cell.ctype == 5 else (str(val_350).strip() if val_350 is not None else "")
        val540_str = "/" if val540_cell.ctype == 5 else (str(val_540).strip() if val_540 is not None else "")
        if val350_str in ["-", "--"]:
            val350_str = "/"
        if val540_str in ["-", "--"]:
            val540_str = "/"

        val350_truly_empty = val350_str in ["", "——"]
        val540_truly_empty = val540_str in ["", "——"]
        if val350_truly_empty and val540_truly_empty:
            current_section = name_str
            continue

        has_leading_space = raw_name != raw_name.lstrip()
        display_name = name_str
        if current_section and has_leading_space:
            display_name = f"{current_section} {name_str}"
        elif current_section and not has_leading_space:
            current_section = None

        item = {
            "name": display_name,
            "unit": str(unit).strip() if unit else "",
            "val_350": val_350 if val350_str not in ["", "——", "/"] else None,
            "val_540": val_540 if val540_str not in ["", "——", "/"] else None,
            "recommendation": str(recommendation).strip() if recommendation and str(recommendation).strip() not in ["", "/", "-", "--"] else "",
        }
        residue_list.append(item)

    return {"residues": residues, "residue_list": residue_list}


def calc_residue_from_distill(distill_data, crude_name):
    """从蒸馏数据计算渣油性质（兜底方案）"""
    residue_data = []
    for temp_range in [">350℃", ">540℃"]:
        temp_val = 350 if temp_range == ">350℃" else 540
        props = {}
        cum_mass = 0
        for d in distill_data:
            parsed = parse_temp_range(d.get("boiling_range", ""))
            if parsed:
                s, _ = parsed
                if s >= temp_val:
                    ym = d.get("yield_per_mass") or 0
                    cum_mass += ym
                    for key in ["density_20", "sulfur", "nitrogen", "acid_value", "viscosity_50", "viscosity_100"]:
                        if d.get(key) is not None:
                            props[key] = d[key]
        props["累积收率%"] = round(cum_mass, 2)
        residue_data.append({"temperature_range": temp_range, "properties": props})
    return residue_data


def parse_fraction_class_sheet(wb, crude_name):
    """解析关键馏分分类 sheet → {"fractions": [...], "classification": str}"""
    try:
        sh = wb.sheet_by_name("关键馏分分类")
    except Exception:
        return None

    fractions = []
    classification = ""

    # 提取分类结论
    # 第一轮：全表扫描完整关键词（如"中间基原油"），优先匹配结论行
    for r in range(sh.nrows):
        row_vals = [str(sh.cell(r, c).value).strip() for c in range(min(sh.ncols, 10))]
        joined = " ".join(row_vals)
        for kw in ["石蜡基原油", "中间基原油", "环烷基原油"]:
            if kw in joined:
                classification = kw
                break
        if classification:
            break

    # 第二轮：仅在含"结论"/"判定"/"结果"的行中扫描短关键词
    if not classification:
        for r in range(sh.nrows):
            row_vals = [str(sh.cell(r, c).value).strip() for c in range(min(sh.ncols, 10))]
            joined = " ".join(row_vals)
            if not any(kw in joined for kw in ["结论", "判定", "结果"]):
                continue
            for kw in ["石蜡基", "中间基", "环烷基"]:
                if kw in joined:
                    classification = kw + "原油"
                    break
            if classification:
                break

    # 第三轮：全表扫描短关键词（最后兜底）
    if not classification:
        for r in range(sh.nrows):
            row_vals = [str(sh.cell(r, c).value).strip() for c in range(min(sh.ncols, 10))]
            joined = " ".join(row_vals)
            for kw in ["石蜡基", "中间基", "环烷基"]:
                if kw in joined:
                    classification = kw + "原油"
                    break
            if classification:
                break

    # 找含 ≥2 个温度范围的表头行
    header_row_idx = -1
    temp_cols = []
    for r in range(min(sh.nrows, 8)):
        candidates = []
        for c in range(sh.ncols):
            v = str(sh.cell(r, c).value).strip()
            if re.search(r'\d{2,}\s*[～~]\s*\d{2,}', v):
                candidates.append((c, v))
        if len(candidates) >= 2:
            header_row_idx = r
            temp_cols = candidates
            break

    if not temp_cols:
        # 兼容旧格式：第一列为温度范围
        for r in range(sh.nrows):
            first = str(sh.cell(r, 0).value).strip()
            if re.search(r'\d+\s*[～~]\s*\d+', first) or re.search(r'\d+\s*℃', first):
                row_data = {"range": first}
                for c in range(1, min(sh.ncols, 8)):
                    v = sh.cell(r, c).value
                    if v and str(v).strip():
                        try:
                            row_data[f"col{c}"] = float(v)
                        except (ValueError, TypeError):
                            row_data[f"col{c}"] = str(v).strip()
                fractions.append(row_data)
        return {"fractions": fractions, "classification": classification}

    paraffinic_by_col = {}
    intermediate_by_col = {}
    naphthenic_by_col = {}
    measured_rows = []
    SKIP_KEYWORDS = ["分类", "结论", "类别", "项目", "基属"]

    for r in range(header_row_idx + 1, sh.nrows):
        first = str(sh.cell(r, 0).value).strip() if sh.cell(r, 0).value is not None else ""
        if not first:
            continue
        col_vals = {}
        for col_idx, _ in temp_cols:
            v = sh.cell(r, col_idx).value
            if v is not None and str(v).strip() not in ["", "/", "-", "--"]:
                col_vals[col_idx] = str(v).strip()
        if not col_vals:
            continue
        if "石蜡基" in first:
            paraffinic_by_col.update(col_vals)
        elif "中间基" in first:
            intermediate_by_col.update(col_vals)
        elif "环烷基" in first:
            naphthenic_by_col.update(col_vals)
        elif not any(kw in first for kw in SKIP_KEYWORDS):
            measured_rows.append((first, col_vals))

    for prop_name, col_vals in measured_rows:
        for col_idx, range_label in temp_cols:
            raw_val = col_vals.get(col_idx)
            if raw_val is None:
                continue
            try:
                measured = float(raw_val)
            except (ValueError, TypeError):
                measured = raw_val
            item = {"temperature_range": range_label, "property": prop_name, "measured": measured}
            if paraffinic_by_col.get(col_idx):
                item["paraffinic_std"] = paraffinic_by_col[col_idx]
            if intermediate_by_col.get(col_idx):
                item["intermediate_std"] = intermediate_by_col[col_idx]
            if naphthenic_by_col.get(col_idx):
                item["naphthenic_std"] = naphthenic_by_col[col_idx]
            fractions.append(item)

    if not fractions and any([paraffinic_by_col, intermediate_by_col, naphthenic_by_col]):
        for col_idx, range_label in temp_cols:
            if paraffinic_by_col.get(col_idx) or intermediate_by_col.get(col_idx) or naphthenic_by_col.get(col_idx):
                item = {"temperature_range": range_label, "property": "特征因数K", "measured": None}
                if paraffinic_by_col.get(col_idx):
                    item["paraffinic_std"] = paraffinic_by_col[col_idx]
                if intermediate_by_col.get(col_idx):
                    item["intermediate_std"] = intermediate_by_col[col_idx]
                if naphthenic_by_col.get(col_idx):
                    item["naphthenic_std"] = naphthenic_by_col[col_idx]
                fractions.append(item)

    return {"fractions": fractions, "classification": classification}


def parse_text_sections(wb, crude_name):
    """解析文字描述 sheet → list[dict]"""
    sections = []
    sheet_names = wb.sheet_names()
    text_sheets = [s for s in sheet_names if "评价" in s or "文本" in s or "文字" in s]

    section_num = 1
    for sheet_name in text_sheets:
        try:
            sh = wb.sheet_by_name(sheet_name)
        except Exception:
            continue
        current_title = ""
        for r in range(min(sh.nrows, 200)):
            for c in range(min(sh.ncols, 3)):
                val = str(sh.cell(r, c).value).strip()
                if not val:
                    continue
                # 检测章节标题：中文编号"一、"/"二、"等、阿拉伯数字编号"1、"/"2."/"1 xxx"等、或已知关键词
                is_heading = (len(val) <= 30) and (
                    bool(re.match(r'^[一二三四五六七八九十]+[、.．]\s*\S', val)) or
                    bool(re.match(r'^\d+[、.．]\s*\S', val)) or
                    bool(re.match(r'^\d+\s+[^\d\s]', val)) or
                    any(kw in val for kw in ["原油评价", "原油性质", "总结", "概述"])
                )
                if is_heading:
                    current_title = val
                elif len(val) > 20:
                    sections.append({
                        "crude_name": crude_name,
                        "section_num": str(section_num),
                        "section_title": current_title,
                        "content": val,
                    })
                    section_num += 1

    if not sections:
        sections.append({
            "crude_name": crude_name,
            "section_num": "1",
            "section_title": "原油一般性质",
            "content": f"{crude_name}原油评价数据已导入系统。",
        })

    return sections


# ---------------------------------------------------------------------------
# 硫/酸分类
# ---------------------------------------------------------------------------

def classify_crude_by_sulfur_acid(properties):
    """根据硫含量和酸值分类原油 → (sulfur_cat, acid_cat)

    硫含量：key 含子串 '硫含量'，<3000 低硫 / <10000 含硫 / >=10000 高硫
    酸值：key 含子串 '酸值'，>1.0 高酸 / >0.5 含酸 / <=0.5 低酸
    """
    if isinstance(properties, str):
        properties = json.loads(properties)

    sulfur_val = None
    acid_val = None
    for key, v in properties.items():
        if "硫含量" in key:
            try:
                sulfur_val = float(v)
            except (ValueError, TypeError):
                pass
        if "酸值" in key:
            try:
                acid_val = float(v)
            except (ValueError, TypeError):
                pass

    sulfur_cat = ""
    acid_cat = ""
    if sulfur_val is not None:
        if sulfur_val < 3000:
            sulfur_cat = "低硫"
        elif sulfur_val < 10000:
            sulfur_cat = "含硫"
        else:
            sulfur_cat = "高硫"
    if acid_val is not None:
        if acid_val > 1.0:
            acid_cat = "高酸"
        elif acid_val > 0.5:
            acid_cat = "含酸"
        else:
            acid_cat = "低酸"

    return sulfur_cat, acid_cat


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def process_excel_file(file_path, crude_name=None, sample_date=None, sample_point=None):
    """处理 Excel 文件 → 结构化 dict（不入库）

    返回:
        {
            "crude_name": str,
            "sample_date": str,       # YYYYMMDD
            "sample_point": str,
            "distillation": [...],
            "crude_properties": {"dict": {...}, "list": [...]},
            "sideline_yields": [...],
            "residue": {"residues": [...], "residue_list": [...]},
            "fraction_class": {"fractions": ..., "classification": str},
            "text_sections": [...],
            "classification": {"sulfur_cat": str, "acid_cat": str, "combined": str},
        }
    """
    import xlrd
    wb = xlrd.open_workbook(file_path)

    # 封面
    cover = parse_cover_sheet(wb)
    if not crude_name:
        crude_name = cover.get("crude_name", "")
    if not crude_name:
        raise ValueError("无法从Excel文件中解析原油名称，请手动输入 --crude-name")
    if not sample_point:
        sample_point = cover.get("sample_point", "")
    if not sample_date:
        sample_date = cover.get("sample_date", "")
    if not sample_date:
        sample_date = "00000000"

    sheet_names = wb.sheet_names()

    # 1. 实沸点
    distill_data = parse_distillation_sheet(wb, crude_name)

    # 2. 原油性质
    crude_props_dict, crude_props_list = parse_crude_properties_sheet(wb, crude_name)

    # 3. 侧线收率（按 SIDELINE_CUTS 累加 yield_per_mass）
    sideline_yields = []
    for sname, cuts in SIDELINE_CUTS.items():
        cum_yield = 0
        for d in distill_data:
            parsed = parse_temp_range(d.get("boiling_range", ""))
            if parsed:
                s, e = parsed
                if s >= cuts["start"] and e <= cuts["end"]:
                    ym = d.get("yield_per_mass") or 0
                    cum_yield += ym
        sideline_yields.append({
            "sideline_name": sname,
            "cut_temp_start": cuts["start"],
            "cut_temp_end": cuts["end"],
            "cut_label": cuts["label"],
            "yield_mass_pct": round(cum_yield, 2),
        })

    # 4. 渣油
    if "渣油" in sheet_names:
        residue_data = parse_residue_sheet(wb, crude_name)
    else:
        residue_data = calc_residue_from_distill(distill_data, crude_name)

    # 5. 关键馏分分类
    fraction_class = None
    if "关键馏分分类" in sheet_names:
        fraction_class = parse_fraction_class_sheet(wb, crude_name)
    if not fraction_class and crude_props_dict:
        fraction_class = {
            "fractions": {
                "密度(20℃)": crude_props_dict.get("密度(20℃)"),
                "硫含量(μg/g)": crude_props_dict.get("硫含量(μg/g)"),
                "酸值(mgKOH/g)": crude_props_dict.get("酸值(mgKOH/g)"),
                "残炭(质量分数)": crude_props_dict.get("残炭(质量分数)"),
                "原油类别": crude_props_dict.get("原油类别", ""),
            },
            "classification": crude_props_dict.get("原油类别", ""),
        }

    # 6. 文字描述
    text_sections = parse_text_sections(wb, crude_name)

    # 7. 硫/酸分类
    sulfur_cat, acid_cat = classify_crude_by_sulfur_acid(crude_props_dict)
    combined = f"{sulfur_cat}{acid_cat}原油" if (sulfur_cat or acid_cat) else ""

    return {
        "crude_name": crude_name,
        "sample_date": sample_date,
        "sample_point": sample_point,
        "sheet_names": sheet_names,
        "distillation": distill_data,
        "crude_properties": {"dict": crude_props_dict, "list": crude_props_list},
        "sideline_yields": sideline_yields,
        "residue": residue_data if isinstance(residue_data, dict) else {"residues": residue_data, "residue_list": []},
        "fraction_class": fraction_class,
        "text_sections": text_sections,
        "classification": {"sulfur_cat": sulfur_cat, "acid_cat": acid_cat, "combined": combined},
    }


# ---------------------------------------------------------------------------
# 可选入库：PostgreSQL
# ---------------------------------------------------------------------------

def import_to_postgres(result):
    """将解析结果导入 PostgreSQL（需 DATABASE_URL 环境变量）"""
    import psycopg2
    database_url = os.environ.get("DATABASE_URL")
    if not database_url:
        raise ValueError("DATABASE_URL 环境变量未设置")

    conn = psycopg2.connect(database_url)
    cur = conn.cursor()
    crude_name = result["crude_name"]
    sample_date = result["sample_date"]
    sample_point = result["sample_point"]

    # 建表（幂等）
    _ensure_tables(cur)

    # crude_oil (upsert)
    props_json = json.dumps(result["crude_properties"]["dict"], ensure_ascii=False)
    list_json = json.dumps(result["crude_properties"]["list"], ensure_ascii=False)
    cur.execute(
        "INSERT INTO crude_oil (crude_name, sample_date, sample_point, properties, properties_list) "
        "VALUES (%s, %s, %s, %s::jsonb, %s::jsonb) "
        "ON CONFLICT (crude_name, sample_date, sample_point) DO UPDATE SET properties=EXCLUDED.properties, properties_list=EXCLUDED.properties_list",
        (crude_name, sample_date, sample_point, props_json, list_json)
    )

    # distillation (delete + insert)
    cur.execute("DELETE FROM distillation WHERE crude_name=%s AND sample_date=%s AND sample_point=%s",
                (crude_name, sample_date, sample_point))
    for row in result["distillation"]:
        cur.execute(
            "INSERT INTO distillation (crude_name, sample_date, sample_point, boiling_range, yield_per_mass, yield_per_vol, density_20, sulfur, nitrogen, acid_value, viscosity_20, viscosity_50, viscosity_80, viscosity_100) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (crude_name, sample_date, sample_point, row.get("boiling_range"), row.get("yield_per_mass"),
             row.get("yield_per_vol"), row.get("density_20"), row.get("sulfur"), row.get("nitrogen"),
             row.get("acid_value"), row.get("viscosity_20"), row.get("viscosity_50"),
             row.get("viscosity_80"), row.get("viscosity_100"))
        )

    # sideline_yield (delete + insert)
    cur.execute("DELETE FROM sideline_yield WHERE crude_name=%s AND sample_date=%s AND sample_point=%s",
                (crude_name, sample_date, sample_point))
    for row in result["sideline_yields"]:
        cur.execute(
            "INSERT INTO sideline_yield (crude_name, sample_date, sample_point, sideline_name, cut_temp_start, cut_temp_end, cut_label, yield_mass_pct) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s)",
            (crude_name, sample_date, sample_point, row["sideline_name"], row["cut_temp_start"],
             row["cut_temp_end"], row["cut_label"], row["yield_mass_pct"])
        )

    # residue_properties (delete + insert)
    cur.execute("DELETE FROM residue_properties WHERE crude_name=%s AND sample_date=%s AND sample_point=%s",
                (crude_name, sample_date, sample_point))
    res_data = result["residue"]
    residues = res_data.get("residues", [])
    residue_list_new = res_data.get("residue_list", [])
    for res in residues:
        props_copy = {k: v for k, v in res.items() if k != "temperature_range"}
        cur.execute(
            "INSERT INTO residue_properties (crude_name, sample_date, sample_point, temperature_range, properties, residue_list) "
            "VALUES (%s,%s,%s,%s,%s::jsonb,%s::jsonb)",
            (crude_name, sample_date, sample_point, res["temperature_range"],
             json.dumps(props_copy, ensure_ascii=False, default=str),
             json.dumps(residue_list_new, ensure_ascii=False, default=str) if residue_list_new else None)
        )

    # keyfraction_class (upsert)
    fc = result["fraction_class"]
    if fc:
        cur.execute(
            "INSERT INTO keyfraction_class (crude_name, sample_date, sample_point, fractions, classification) "
            "VALUES (%s,%s,%s,%s::jsonb,%s) "
            "ON CONFLICT (crude_name, sample_date, sample_point) DO UPDATE SET fractions=EXCLUDED.fractions, classification=EXCLUDED.classification",
            (crude_name, sample_date, sample_point,
             json.dumps(fc.get("fractions", {}), ensure_ascii=False, default=str),
             fc.get("classification", ""))
        )

    # text_description (delete + insert)
    cur.execute("DELETE FROM text_description WHERE crude_name=%s AND sample_date=%s AND sample_point=%s",
                (crude_name, sample_date, sample_point))
    for sec in result["text_sections"]:
        cur.execute(
            "INSERT INTO text_description (crude_name, sample_date, sample_point, section_num, section_title, content) "
            "VALUES (%s,%s,%s,%s,%s,%s)",
            (crude_name, sample_date, sample_point, sec.get("section_num"), sec.get("section_title"), sec.get("content"))
        )

    conn.commit()
    cur.close()
    conn.close()
    print(f"[crude-assay-import] Imported {crude_name} ({sample_date}/{sample_point}) to PostgreSQL.")


def _ensure_tables(cur):
    """幂等建表"""
    cur.execute("""
        CREATE TABLE IF NOT EXISTS crude_oil (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            properties JSONB,
            properties_list JSONB,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(crude_name, sample_date, sample_point)
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS distillation (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            boiling_range VARCHAR(50),
            yield_per_mass DOUBLE PRECISION,
            yield_total_mass DOUBLE PRECISION,
            yield_per_vol DOUBLE PRECISION,
            yield_total_vol DOUBLE PRECISION,
            density_20 DOUBLE PRECISION,
            sulfur DOUBLE PRECISION,
            nitrogen DOUBLE PRECISION,
            acid_value DOUBLE PRECISION,
            viscosity_20 DOUBLE PRECISION,
            viscosity_50 DOUBLE PRECISION,
            viscosity_80 DOUBLE PRECISION,
            viscosity_100 DOUBLE PRECISION
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS sideline_yield (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            sideline_name VARCHAR(50) NOT NULL,
            cut_temp_start DOUBLE PRECISION,
            cut_temp_end DOUBLE PRECISION,
            cut_label VARCHAR(50),
            yield_mass_pct DOUBLE PRECISION
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS residue_properties (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            temperature_range VARCHAR(20),
            properties JSONB,
            residue_list JSONB
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS keyfraction_class (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            fractions JSONB,
            classification TEXT,
            UNIQUE(crude_name, sample_date, sample_point)
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS text_description (
            id SERIAL PRIMARY KEY,
            crude_name VARCHAR(200) NOT NULL,
            sample_date VARCHAR(8) DEFAULT '' NOT NULL,
            sample_point VARCHAR(200) DEFAULT '' NOT NULL,
            section_num VARCHAR(10),
            section_title VARCHAR(100),
            content TEXT
        )
    """)


# ---------------------------------------------------------------------------
# 可选入库：ChromaDB
# ---------------------------------------------------------------------------

def import_to_chromadb(result):
    """将文字描述导入 ChromaDB 向量库"""
    import chromadb
    chroma_path = os.environ.get("CHROMA_PATH", os.path.join(os.path.dirname(__file__), "..", "data", "chroma_db"))
    client = chromadb.PersistentClient(path=chroma_path)
    collection = client.get_or_create_collection(
        name="crude_oil_knowledge",
        metadata={"hnsw:space": "cosine"}
    )

    crude_name = result["crude_name"]
    sample_date = result["sample_date"]
    sample_point = result["sample_point"]

    for i, sec in enumerate(result["text_sections"]):
        content = sec.get("content", "").strip()
        if not content:
            continue
        section_num = sec.get("section_num", str(i + 1))
        section_title = sec.get("section_title", "")
        doc_id = f"{crude_name}_{sample_date}_{sample_point}_{section_num}_{i}"
        collection.add(
            documents=[content],
            ids=[doc_id],
            metadatas=[{
                "crude_name": crude_name,
                "sample_date": sample_date,
                "sample_point": sample_point,
                "section_num": section_num,
                "section_title": section_title,
                "type": "text_description",
            }]
        )
    print(f"[crude-assay-import] Imported {len(result['text_sections'])} text sections to ChromaDB.")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="解析原油评价报告 Excel(.xls)")
    parser.add_argument("file", help="Excel 文件路径 (.xls)")
    parser.add_argument("--crude-name", default=None, help="原油名称（默认从封面提取）")
    parser.add_argument("--sample-date", default=None, help="采样日期 YYYYMMDD（默认从封面提取）")
    parser.add_argument("--sample-point", default=None, help="采样点（默认从封面提取）")
    parser.add_argument("--json", default=None, help="输出结构化 JSON 到指定文件")
    parser.add_argument("--import-pg", action="store_true", help="导入 PostgreSQL（需 DATABASE_URL）")
    parser.add_argument("--import-chroma", action="store_true", help="导入 ChromaDB（需 chromadb）")
    parser.add_argument("--quiet", action="store_true", help="静默模式，不打印解析摘要")
    args = parser.parse_args()

    result = process_excel_file(
        args.file,
        crude_name=args.crude_name,
        sample_date=args.sample_date,
        sample_point=args.sample_point,
    )

    if not args.quiet:
        print(f"[crude-assay-import] {result['crude_name']} ({result['sample_date']}/{result['sample_point']})")
        print(f"  sheets: {result['sheet_names']}")
        print(f"  distillation rows: {len(result['distillation'])}")
        print(f"  crude properties: {len(result['crude_properties']['dict'])} items")
        print(f"  sideline yields: {len(result['sideline_yields'])}")
        print(f"  residue: {len(result['residue'].get('residues', []))} residues, {len(result['residue'].get('residue_list', []))} list entries")
        fc = result['fraction_class']
        print(f"  fraction class: {len(fc.get('fractions', []) if fc else [])} items, classification={fc.get('classification', '') if fc else ''}")
        print(f"  text sections: {len(result['text_sections'])}")
        cls = result['classification']
        print(f"  classification: {cls['combined'] or '(无法分类)'}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2, default=str)
        if not args.quiet:
            print(f"[crude-assay-import] JSON written to {args.json}")

    if args.import_pg:
        import_to_postgres(result)

    if args.import_chroma:
        import_to_chromadb(result)

    return 0


if __name__ == "__main__":
    sys.exit(main())
