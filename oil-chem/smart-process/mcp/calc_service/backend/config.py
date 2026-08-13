# -*- coding: utf-8 -*-
"""
calc_service 全局配置与业务常量中心。

原 solve/ 模块中，装置ID、原油系数表、默认收率等硬编码散落在
solver.py、web_app.py、device_input_calculator.py 等 5+ 文件中，
且原油系数表重复了 3 次。本文件将它们集中到一处管理。
"""
import os

# ── Excel 数据文件路径 ──
# 仅 migrate_excel_to_db.py 迁移脚本读取一次；运行时存储已切到 PostgreSQL（见下）。
# 保留作为历史数据源/重置入口，运行时不再访问。
EXCEL_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "refinery_data.xlsx"
)

# ── PostgreSQL 数据库（calc_service 运行时存储，schema solve_db）──
# 默认沿用主项目 backend 的业务库；可由环境变量 DATABASE_URL 覆盖。
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://huilian:huilian2026@localhost:5432/huilian"
)
DB_SCHEMA = "solve_db"

# ── 装置ID ──
DEVICE_CYJQ = "cyjq_01"            # 柴油加氢装置
DEVICE_LYJQ = "lyjq_01"            # 蜡油加氢装置
DEVICE_HC_TANK = "hc_tank_01"      # 蜡油储罐
DEVICE_GYRLY_TANK = "gyrly_tank_01"  # 柴油组分储罐
DEVICE_CJY = "cjy_01"              # 常减压装置

# 需要做 XY 分流 / 收率切换的装置
YIELD_SWITCH_DEVICES = (DEVICE_CYJQ, DEVICE_LYJQ)

# ── 原油类型ID（与 public.crude_types 主数据表对齐）──
CRUDE_BOZHONG = "bozhong_25_1"
CRUDE_QINHUANGDAO = "qinhuangdao"
CRUDE_CAOFEIDIAN = "caofeidian"
CRUDE_LUDA = "luda_10_1"
DEFAULT_CRUDE_TYPE = CRUDE_BOZHONG

# ── 原油品种系数表 [已废弃] ──
# 数据已迁移到 products 表（material_type='main_feed' 的 yield_rate 字段）
# 请使用 scenario.get_feed_ratio() 获取进料配比
# CRUDE_COEFFICIENTS = {
#     "cyjq": {
#         CRUDE_BOZHONG: 0.9773,
#         CRUDE_NANPU: 0.9764,
#         CRUDE_CAOFEIDIAN: 0.9779,
#         CRUDE_LH_LD: 0.9779,
#     },
#     "lyjq": {
#         CRUDE_BOZHONG: 0.9017,
#         CRUDE_NANPU: 0.9046,
#         CRUDE_CAOFEIDIAN: 0.8919,
#         CRUDE_LH_LD: 0.8919,
#     },
# }

# ── 默认收率 ──
DEFAULT_JIAN1_YIELD = 0.08  # 原 comprehensive_solve / optimize_valve_switching 中硬编码

# 减一线分流模式（替代无意义的 X_ZERO/Y_ZERO）
MODE_JIAN1_TO_WAX = 'JIAN1_TO_WAX'        # 减一线去蜡油加氢（原 X_ZERO）
MODE_JIAN1_TO_DIESEL = 'JIAN1_TO_DIESEL'  # 减一线去柴油加氢（原 Y_ZERO）

YIELD_MODE_LABELS = {
    MODE_JIAN1_TO_WAX: '减一线去蜡油加氢',
    MODE_JIAN1_TO_DIESEL: '减一线去柴油加氢',
}

# ── 收率字段映射表 ──
# (工况, 减一线模式) → Product dataclass 上的字段名
# 原 solver.py 和 web_app.py 各维护一套，本表统一之。
YIELD_FIELD_MAP = {
    # 非航煤工况
    ("normal",  MODE_JIAN1_TO_WAX):    "yield_rate",
    ("normal",  MODE_JIAN1_TO_DIESEL): "yield_rate_2",
    # 航煤工况
    ("hangmei", MODE_JIAN1_TO_WAX):    "yield_rate_3",
    ("hangmei", MODE_JIAN1_TO_DIESEL): "yield_rate_4",
}

# 当航煤收率字段为 0 时的回退字段
YIELD_FALLBACK_MAP = {
    "yield_rate_3": "yield_rate",
    "yield_rate_4": "yield_rate_2",
}

# ── 排厂 Excel sheet 名称 ──
SHEET_DEVICES = "devices"
SHEET_PRODUCTS = "products"
SHEET_CONNECTIONS = "connections"
SHEET_ENERGY = "energy"
SHEET_PRICE_COST = "price_cost"
SHEET_PRODUCTION_PLANS_INPUT = "production_plans_input"
SHEET_PRODUCTION_PLAN_DETAILS = "production_plan_details"
SHEET_SCHEDULING_TASKS = "scheduling_tasks"

# ── 装置 ID 映射（calc_service → FastAPI md_device）──
# 用于从价格模块获取装置加工成本。
DEVICE_ID_MAPPING = {
    DEVICE_CJY: 1,            # cjy_01 → 2#常减压
    DEVICE_CYJQ: 2,           # cyjq_01 → 1#加裂（1#柴加）
    DEVICE_LYJQ: 3,           # lyjq_01 → 1#蜡加
}

# ── 价格模块 API（FastAPI 8000，统一价格源）──
# calc_service 不再维护 price_cost 表，所有价格通过此 API 从价格模块获取。
PRICE_API_URL = os.getenv("PRICE_API_URL", "http://localhost:8000/api/v1")
PRICE_API_TIMEOUT = int(os.getenv("PRICE_API_TIMEOUT", "5"))  # 秒

# ── 服务端口（与现有 solve/ 5080 并存）──
SERVER_HOST = "0.0.0.0"
SERVER_PORT = 5081

# [已废弃] 请使用 scenario.get_feed_ratio() 获取进料配比
# def get_crude_coefficient(device_id: str, crude_type: str = None) -> float:
#     """获取指定装置在指定原油品种下的系数，未知则返回 1.0。"""
#     ...
