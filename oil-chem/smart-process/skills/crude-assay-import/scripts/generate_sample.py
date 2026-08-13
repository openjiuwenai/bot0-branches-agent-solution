#!/usr/bin/env python3
"""生成脱敏的原油评价报告示例 .xls，用于 crude-assay-import skill 的演示和测试。

生成的 Excel 包含 6 个 sheet，覆盖所有解析路径：
  封面、原油性质、实沸点、渣油、关键馏分分类、原油评价文字描述

用法: python generate_sample.py [output.xls]
"""
import sys
import xlwt


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "示例原油评价报告.xls"
    wb = xlwt.Workbook(encoding="utf-8")

    # 样式
    title_style = xlwt.easyxf("font: bold on; align: horiz center")
    header_style = xlwt.easyxf("font: bold on; align: horiz center; borders: top thin, bottom thin")
    section_style = xlwt.easyxf("font: bold on")

    # ── sheet 1: 封面 ──
    sh = wb.add_sheet("封面")
    sh.write(0, 0, "原油评价报告", title_style)
    sh.write(2, 0, "原油名称")
    sh.write(2, 2, "示例油种A（一号站）")
    sh.write(3, 0, "采样日期")
    # xlwt 日期单元格
    # xlwt 需要日期格式的 XF style，xlrd 才会识别为 ctype==3
    date_style = xlwt.easyxf(num_format_str="YYYY-MM-DD")
    sh.write(3, 2, xlrd_date(2025, 1, 15), date_style)
    sh.col(2).width = 6000

    # ── sheet 2: 原油性质 ──
    sh = wb.add_sheet("原油性质")
    sh.write(0, 0, "表1 原油一般性质分析", title_style)
    # 表头: 分析项目 | 单位 | 方法/代号 | 分析结果
    headers = ["分析项目", "单位", "方法/代号", "分析结果"]
    for c, h in enumerate(headers):
        sh.write(1, c, h, header_style)
    # 数据行 (从行2开始, 0-based)
    # 注意: section 标题行（无前导空格+值为空）会被识别为 section
    # 子项行（有前导空格）会拼接 section name
    props = [
        # section 标题（值为空，无前导空格）
        ("密度(20℃)", "g/cm³", "GB/T1884", 0.8725),
        ("API°", "", "ASTM D1298", 30.50),
        ("运动粘度(50℃)", "mm²/s", "GB/T265", 15.28),
        ("凝点", "℃", "GB/T510", 28),
        ("酸值", "mgKOH/g", "GB/T7304", 0.32),
        ("硫含量(μg/g)", "μg/g", "GB/T17040", 2800),
        ("氮含量(μg/g)", "μg/g", "GB/T9170", 1200),
        ("残炭(质量分数)", "%", "GB/T268", 3.25),
        ("蜡含量(质量分数)", "%", "SY/T0537", 12.50),
        ("胶质(质量分数)", "%", "RIPP-7", 8.20),
        ("沥青质(质量分数)", "%", "SY/T7550", 1.80),
        ("镍(μg/g)", "μg/g", "ASTM D5708", 8.5),
        ("钒(μg/g)", "μg/g", "ASTM D5708", 3.2),
        ("原油类别", "", "", "低硫中间基"),
    ]
    for i, (name, unit, method, val) in enumerate(props):
        r = 2 + i
        sh.write(r, 0, name)
        sh.write(r, 1, unit)
        sh.write(r, 2, method)
        sh.write(r, 3, val)

    # ── sheet 3: 实沸点（标准格式：行0标题/行1-2表头/行3+数据）──
    sh = wb.add_sheet("实沸点")
    sh.write(0, 0, "实沸点蒸馏数据", title_style)
    # 行1-2 表头（合并单元格效果）
    distill_headers = ["沸点范围(℃)", "每馏分收率%(m/m)", "总收率%(m/m)",
                       "每馏分收率%(V/V)", "总收率%(V/V)", "密度(20℃)g/cm³",
                       "倾点(℃)", "", "酸值(mgKOH/g)", "硫含量(μg/g)", "氮含量(μg/g)"]
    for c, h in enumerate(distill_headers):
        sh.write(1, c, h, header_style)
    sh.write(2, 0, "", header_style)  # 合并行

    # 数据行（从行3开始）
    distill_data = [
        ("<60",        2.50,  2.50,  3.10,  3.10, 0.6520, None, None, None, 100,  50),
        ("60～80",     1.80,  4.30,  2.20,  5.30, 0.6680, None, None, 0.05, 200,  80),
        ("80～100",    2.30,  6.60,  2.70,  8.00, 0.6820, None, None, 0.08, 350, 120),
        ("100～120",   2.10,  8.70,  2.40, 10.40, 0.6980, None, None, 0.10, 500, 150),
        ("120～140",   2.50, 11.20,  2.80, 13.20, 0.7120, None, None, 0.12, 650, 180),
        ("140～160",   2.80, 14.00,  3.10, 16.30, 0.7250, None, None, 0.15, 800, 200),
        ("160～180",   3.00, 17.00,  3.30, 19.60, 0.7380, -45,  None, 0.18, 950,  230),
        ("180～200",   3.20, 20.20,  3.50, 23.10, 0.7510, -38,  None, 0.22, 1100, 250),
        ("200～220",   3.50, 23.70,  3.80, 26.90, 0.7640, -30,  None, 0.28, 1250, 280),
        ("220～240",   3.80, 27.50,  4.10, 31.00, 0.7750, -22,  None, 0.35, 1400, 300),
        ("240～260",   4.00, 31.50,  4.30, 35.30, 0.7850, -15,  None, 0.42, 1550, 320),
        ("260～280",   4.20, 35.70,  4.50, 39.80, 0.7940,  -8,  None, 0.50, 1700, 340),
        ("280～300",   4.50, 40.20,  4.80, 44.60, 0.8020,   0,  None, 0.58, 1850, 360),
        ("300～320",   4.80, 45.00,  5.10, 49.70, 0.8100,   5,  None, 0.68, 2000, 380),
        ("320～340",   5.00, 50.00,  5.30, 55.00, 0.8180,  12,  None, 0.80, 2200, 400),
        ("340～360",   4.80, 54.80,  5.10, 60.10, 0.8250,  18,  None, 0.92, 2400, 420),
        ("360～370",   3.50, 58.30,  3.70, 63.80, 0.8320,  25,  None, 1.05, 2600, 440),
        ("370～395",   4.20, 62.50,  4.40, 68.20, 0.8400,  30,  None, 1.15, 2750, 460),
        ("395～420",   4.50, 67.00,  4.70, 72.90, 0.8480,  35,  None, 1.28, 2900, 480),
        ("420～450",   5.00, 72.00,  5.20, 78.10, 0.8550,  40,  None, 1.42, 3050, 500),
        ("450～475",   4.80, 76.80,  5.00, 83.10, 0.8620,  45,  None, 1.55, 3200, 520),
        ("475～500",   4.50, 81.30,  4.70, 87.80, 0.8700,  50,  None, 1.68, 3350, 540),
        ("500～520",   3.80, 85.10,  3.90, 91.70, 0.8780,  55,  None, 1.82, 3500, 560),
        ("520～540",   3.50, 88.60,  3.60, 95.30, 0.8860,  58,  None, 1.95, 3650, 580),
        (">540",       8.50, 97.10,  8.20, 103.50, 0.9450, 65, None, 2.80, 4200, 700),
    ]
    for i, row in enumerate(distill_data):
        r = 3 + i
        for c, val in enumerate(row):
            if val is not None:
                sh.write(r, c, val)

    # ── sheet 4: 渣油 ──
    sh = wb.add_sheet("渣油")
    sh.write(0, 0, "渣油性质分析", title_style)
    residue_headers = ["分析项目", "单位", "方法/代号", ">350℃", ">540℃", "推荐方法"]
    for c, h in enumerate(residue_headers):
        sh.write(1, c, h, header_style)
    residue_data = [
        ("占原油收率", "%(m/m)", "", 58.30, 11.40, ""),
        ("密度(20℃)", "g/cm³", "GB/T1884", 0.9150, 0.9450, ""),
        ("运动粘度(100℃)", "mm²/s", "GB/T265", 45.50, 1200.00, ""),
        ("残炭(质量分数)", "%", "GB/T268", 8.50, 18.20, ""),
        ("硫含量", "μg/g", "GB/T17040", 3500, 5200, ""),
        ("氮含量", "μg/g", "GB/T9170", 1800, 3500, ""),
        ("酸值", "mgKOH/g", "GB/T7304", 1.25, 2.80, ""),
        ("饱和分", "%", "SH/T0509", 45.20, 38.50, ""),
        ("芳香分", "%", "SH/T0509", 28.50, 30.20, ""),
        ("胶质", "%", "SH/T0509", 18.30, 20.80, ""),
        ("沥青质", "%", "SH/T0509", 8.00, 10.50, ""),
    ]
    for i, (name, unit, method, v350, v540, rec) in enumerate(residue_data):
        r = 2 + i
        sh.write(r, 0, name)
        sh.write(r, 1, unit)
        sh.write(r, 2, method)
        sh.write(r, 3, v350)
        sh.write(r, 4, v540)
        sh.write(r, 5, rec)

    # ── sheet 5: 关键馏分分类 ──
    sh = wb.add_sheet("关键馏分分类")
    sh.write(0, 0, "关键馏分分类", title_style)
    # 转置结构：温度范围在列头
    sh.write(1, 0, "分类标准")
    sh.write(1, 1, "250~275℃")
    sh.write(1, 2, "395~425℃")
    sh.write(1, 3, "475~500℃")

    # 标准值行
    sh.write(2, 0, "石蜡基")
    sh.write(2, 1, "≤0.840")
    sh.write(2, 2, "≤0.870")
    sh.write(2, 3, "≤0.890")

    sh.write(3, 0, "中间基")
    sh.write(3, 1, "0.840~0.870")
    sh.write(3, 2, "0.870~0.900")
    sh.write(3, 3, "0.890~0.910")

    sh.write(4, 0, "环烷基")
    sh.write(4, 1, "≥0.870")
    sh.write(4, 2, "≥0.900")
    sh.write(4, 3, "≥0.910")

    # 实测值行
    sh.write(5, 0, "密度(20℃)")
    sh.write(5, 1, 0.852)
    sh.write(5, 2, 0.882)
    sh.write(5, 3, 0.905)

    sh.write(6, 0, "分类结论")
    sh.write(6, 1, "中间基原油")

    # ── sheet 6: 原油评价文字描述 ──
    sh = wb.add_sheet("原油评价文字描述")
    sh.write(0, 0, "原油评价文字描述", title_style)
    sh.write(1, 0, "一、原油一般性质")
    sh.write(2, 0, "示例油种A原油为低硫中间基原油，密度0.8725 g/cm³，API度30.5。"
                     "该原油运动粘度(50℃)为15.28 mm²/s，凝点28℃，酸值0.32 mgKOH/g，"
                     "硫含量2800 μg/g，属于低硫原油。残炭3.25%，蜡含量12.5%，"
                     "胶质8.2%，沥青质1.8%，镍含量8.5 μg/g，钒含量3.2 μg/g。")
    sh.write(3, 0, "二、原油实沸点蒸馏性质")
    sh.write(4, 0, "实沸点蒸馏共25个馏分，初馏点至540℃以上总收率约97.1%。"
                     "石脑油段(初馏点~180℃)收率约17.0%，柴油段(180~370℃)收率约41.3%，"
                     "蜡油段(370~540℃)收率约28.6%，渣油(>540℃)收率约8.5%。")
    sh.write(5, 0, "三、加工建议")
    sh.write(6, 0, "该原油为低硫中间基原油，适合催化裂化和加氢裂化工艺。"
                     "石脑油段可作重整原料，柴油段需加氢精制，蜡油段可作催化裂化或加氢裂化原料。"
                     "渣油残炭较高(18.2%)，建议走减压渣油加氢或焦化路线。")

    wb.save(output)
    print(f"示例文件已生成: {output}")
    print(f"包含 6 个 sheet: 封面、原油性质、实沸点、渣油、关键馏分分类、原油评价文字描述")


def xlrd_date(y, m, d):
    """构造 xlrd 兼容的日期值（用于 xlwt 写入后被 xlrd ctype==3 识别）"""
    import datetime
    dt = datetime.date(y, m, d)
    # Excel 日期序列号（以1899-12-30为第0天）
    origin = datetime.date(1899, 12, 30)
    return (dt - origin).days


if __name__ == "__main__":
    main()
