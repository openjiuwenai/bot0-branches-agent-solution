# -*- coding: utf-8 -*-
"""PostgreSQL 数据库基础设施（calc_service 存储层）。

对齐主项目 backend/app/db/database.py 的访问模式：
  - SQLAlchemy 2.x 同步 + psycopg2-binary
  - create_engine(pool_size=5, max_overflow=10) 单例
  - sessionmaker(autocommit=False, autoflush=False)
  - text() 为主、手动 commit/rollback、upsert 用 ON CONFLICT、JSON 用 JSONB

差异：主项目是 FastAPI（Depends(get_db) 注入 session），calc_service 是 Flask，
故提供 get_session() 上下文管理器替代；并自带 init_db() 建 schema+表。

所有业务表建在 solve_db schema 下；engine 通过 connect_args 设
search_path=solve_db,public，故 SQL 中可直接用裸表名。
"""
import os
from contextlib import contextmanager

from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session, sessionmaker

from ..config import DATABASE_URL, DB_SCHEMA

# 绕过 KingbaseES 版本解析报错（金仓返回的非标准版本号会使 SQLAlchemy 解析失败）。
# 对齐主项目 backend/app/db/database.py:17-20；纯 PostgreSQL 环境下无副作用。
try:
    from sqlalchemy.dialects.postgresql import base as _pg_base

    def _mock_get_server_version(self, connection):
        return (8, 6, 0)

    _pg_base.PGDialect._get_server_version_info = _mock_get_server_version
except Exception:  # pragma: no cover - 仅在 sqlalchemy 版本异常时跳过
    pass


# ── engine / SessionLocal ────────────────────────────────────────────────
# search_path=solve_db,public：裸表名优先命中 solve_db，同时保留对 public 的访问能力。
engine = create_engine(
    DATABASE_URL,
    echo=False,
    pool_size=5,
    max_overflow=10,
    connect_args={"options": f"-csearch_path={DB_SCHEMA},public"},
)
SessionLocal = sessionmaker(bind=engine, autocommit=False, autoflush=False)


@contextmanager
def get_session():
    """业务 DB session 上下文管理器（Flask 风格，替代 FastAPI 的 Depends(get_db)）。

    用法：
        with get_session() as db:
            repo = SchedulingRepository(db)
            repo.save_production_plan_details(details)
            repo.save_scheduling_task(task)
            db.commit()        # 多步写包成一个事务，由调用方提交
    未显式 commit 即退出时，session 关闭即回滚（autocommit=False）。
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# ── 建表 DDL ─────────────────────────────────────────────────────────────
# 列名统一小写下划线（PG 惯例 + 对齐主项目）；Repository 负责 DB 列名 ↔ dataclass
# 字段名的映射。收率字段存小数 NUMERIC(6,4)（迁移时 Excel 百分比 ÷100）。

_DDL = f"""
CREATE SCHEMA IF NOT EXISTS {DB_SCHEMA};

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.devices (
    device_id              VARCHAR(32)  PRIMARY KEY,
    name                   VARCHAR(128) NOT NULL,
    type                   VARCHAR(16)  NOT NULL,   -- start | normal | tank
    max_capacity           NUMERIC(14,3),
    safety_stock_thrd      NUMERIC(14,3),
    low_safety_thrd        NUMERIC(14,3),
    current_capacity       NUMERIC(14,3),
    refinery_unit_load_pct NUMERIC(5,2),
    device_id_2            VARCHAR(32),
    backend_device_id      INTEGER,                  -- 对应慧炼 md_device 主键
    tank_category          VARCHAR(16),              -- 罐分类: intermediate/product/crude (仅 type=tank)
    note                   TEXT,
    enabled                BOOLEAN DEFAULT TRUE      -- 启用/停用：停用时从物流拓扑和计算中完全移除
);

-- 中间罐月初容量（按月配置，仅 intermediate 罐使用）
CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.tank_monthly_initial (
    tank_id           VARCHAR(32)  NOT NULL,
    year_month        VARCHAR(7)   NOT NULL,   -- 如 '2026-07'
    initial_capacity  NUMERIC(14,3),
    PRIMARY KEY (tank_id, year_month)
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.products (
    product_id        VARCHAR(64),
    name              VARCHAR(128),
    source_device_id  VARCHAR(32),
    yield_rate        NUMERIC(6,4),   -- 小数，0.0000~1.0000
    yield_rate_2      NUMERIC(6,4),
    yield_rate_3      NUMERIC(6,4),
    yield_rate_4      NUMERIC(6,4),
    material_type     VARCHAR(16) DEFAULT 'product',  -- product / main_feed / auxiliary
    is_final          BOOLEAN,
    note              TEXT,
    crude_type        VARCHAR(64),
    PRIMARY KEY (product_id, crude_type)
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.connections (
    connection_id     VARCHAR(32) PRIMARY KEY,
    from_device_id    VARCHAR(32),
    from_product_id   VARCHAR(64),
    to_device_id      VARCHAR(32),
    priority          SMALLINT,
    is_unique_target  BOOLEAN,
    special_var       VARCHAR(4)      -- X / Y / J / K，可空
);

-- 物流边表（归一化模型：一行 = 一条有向边）
CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.material_flows (
    flow_id            VARCHAR(64) PRIMARY KEY,
    source_type        VARCHAR(16) NOT NULL,   -- device | external | tank
    source_device_id   VARCHAR(32),            -- source_to_tank/direct/final: 源装置
    source_product_id  VARCHAR(64),            -- source_to_tank/direct/final: 源产品
    source_name        VARCHAR(64),            -- source_type=external 时填（如"曹妃甸原油"）
    tank_id            VARCHAR(32),            -- source_to_tank 目标罐 / tank_to_target 来源罐 / final 目标成品罐
    target_device_id   VARCHAR(32),            -- tank_to_target/direct/input: 目标装置
    target_product_id  VARCHAR(64),            -- tank_to_target/direct: 目标装置的进料产品(main_feed/auxiliary的product.id)
    flow_type          VARCHAR(16) NOT NULL,   -- source_to_tank | tank_to_target | direct | final | input
    special_var        VARCHAR(4),             -- X / Y 分流标记（仅 source_to_tank）
    priority           SMALLINT DEFAULT 1,
    is_unique_target   BOOLEAN DEFAULT FALSE,
    split_ratio        NUMERIC(5,4) DEFAULT 1.0  -- 罐→多装置的分配比例（仅 tank_to_target）
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.energy (
    id                   VARCHAR(32) PRIMARY KEY,
    device_id            VARCHAR(32),
    consumption_per_ton  NUMERIC(18,8),
    price_per_unit       NUMERIC(14,4),
    energy_type          VARCHAR(64)
);

-- ============================================================
-- 调整1：装置/罐物理分表（devices → devices_units + devices_tanks）
-- ============================================================
CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.devices_units (
    device_id           varchar(32)  NOT NULL,
    name                varchar(128) NOT NULL,
    type                varchar(16)  NOT NULL,  -- normal / start
    max_capacity        numeric(14,3),
    safety_stock_thrd   numeric(14,3),
    low_safety_thrd     numeric(14,3),
    current_capacity    numeric(14,3),
    refinery_unit_load_pct numeric(5,2),
    device_id_2         varchar(32),
    backend_device_id   integer,       -- 关联 public.md_device.id
    note                text,
    enabled             boolean DEFAULT true,
    CONSTRAINT devices_units_pkey PRIMARY KEY (device_id),
    CONSTRAINT devices_units_type_chk CHECK (type IN ('normal', 'start'))
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.devices_tanks (
    device_id           varchar(32)  NOT NULL,
    name                varchar(128) NOT NULL,
    max_capacity        numeric(14,3),
    safety_stock_thrd   numeric(14,3),
    low_safety_thrd     numeric(14,3),
    current_capacity    numeric(14,3),
    refinery_unit_load_pct numeric(5,2),
    tank_category       varchar(16)  NOT NULL,  -- intermediate / product / crude
    material_id         integer,     -- 关联 public.md_material.id（储罐唯一物料）
    note                text,
    enabled             boolean DEFAULT true,
    CONSTRAINT devices_tanks_pkey PRIMARY KEY (device_id),
    CONSTRAINT devices_tanks_category_chk CHECK (tank_category IN ('intermediate', 'product', 'crude'))
);

-- ============================================================
-- 调整2：侧线/收率拆分（products → side_lines + device_yields）
-- ============================================================
CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.side_lines (
    side_line_id        varchar(64)  NOT NULL,  -- 原 products.product_id
    name                varchar(128),
    source_device_id    varchar(32),
    material_type       varchar(16) DEFAULT 'product',  -- product / main_feed / auxiliary
    is_final            boolean,
    material_id         integer,      -- 关联 public.md_material.id（替代 product_material_mapping）
    note                text,
    CONSTRAINT side_lines_pkey PRIMARY KEY (side_line_id),
    CONSTRAINT side_lines_material_type_chk CHECK (material_type IN ('product', 'main_feed', 'auxiliary'))
);
CREATE INDEX IF NOT EXISTS idx_side_lines_source_device ON {DB_SCHEMA}.side_lines (source_device_id);
CREATE INDEX IF NOT EXISTS idx_side_lines_material_id ON {DB_SCHEMA}.side_lines (material_id);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.device_yields (
    side_line_id        varchar(64)  NOT NULL,
    crude_type          varchar(64)  NOT NULL,
    yield_rate          numeric(6,4),
    yield_rate_2        numeric(6,4),
    yield_rate_3        numeric(6,4),
    yield_rate_4        numeric(6,4),
    CONSTRAINT device_yields_pkey PRIMARY KEY (side_line_id, crude_type),
    CONSTRAINT device_yields_fk_side_line FOREIGN KEY (side_line_id)
        REFERENCES {DB_SCHEMA}.side_lines (side_line_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.production_plans_input (
    planned_month                VARCHAR(7),
    crude_type_id                VARCHAR(64),
    crude_type_name              VARCHAR(64),
    arrival_plan                 JSONB,
    monthly_processing_capacity  NUMERIC,
    current_stock                NUMERIC,
    max_level_stock              NUMERIC,
    min_level_stock              NUMERIC,
    cost                         NUMERIC,
    PRIMARY KEY (planned_month, crude_type_id)
);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.production_plan_details (
    id                  VARCHAR(64) PRIMARY KEY,
    plan_id             VARCHAR(32),
    plan_date           DATE,
    day_of_month        SMALLINT,
    daily_input         NUMERIC,
    blend_detail        JSONB,
    crude_stock_status  JSONB,
    device_load_rate    NUMERIC(8,4),
    hours               NUMERIC
);
CREATE INDEX IF NOT EXISTS idx_plan_details_plan_id ON {DB_SCHEMA}.production_plan_details(plan_id);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.cp_sat_plan_details (
    id                  VARCHAR(64) PRIMARY KEY,
    plan_id             VARCHAR(32),
    plan_date           DATE,
    day_of_month        SMALLINT,
    daily_input         NUMERIC,
    blend_detail        JSONB,
    crude_stock_status  JSONB,
    device_load_rate    NUMERIC(8,4),
    hours               NUMERIC
);
CREATE INDEX IF NOT EXISTS idx_cpsat_details_plan_id ON {DB_SCHEMA}.cp_sat_plan_details(plan_id);

CREATE TABLE IF NOT EXISTS {DB_SCHEMA}.scheduling_tasks (
    plan_id       VARCHAR(32) PRIMARY KEY,
    planned_month VARCHAR(7),
    status        VARCHAR(32),      -- draft | generated | locked
    locked        BOOLEAN,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    generated_at  TIMESTAMPTZ
);

-- ── 油种主数据表（建在 public schema，供慧炼主程序和 calc_service 共用）──
CREATE TABLE IF NOT EXISTS public.crude_types (
    crude_type_id    VARCHAR(64) PRIMARY KEY,   -- 统一标识，如 bozhong_25_1
    crude_name       VARCHAR(128) NOT NULL,     -- 中文名称，如 渤中25-1
    crude_code       VARCHAR(20),               -- 简码，如 BZ5
    aliases          TEXT[],                    -- 别名数组
    is_active        BOOLEAN DEFAULT true,      -- 是否在用
    is_default       BOOLEAN DEFAULT false,     -- 是否为 default 通配油种
    sort_order       INT DEFAULT 0,             -- 排序
    note             TEXT                       -- 备注
);

-- 幂等插入种子数据
INSERT INTO public.crude_types (crude_type_id, crude_name, crude_code, aliases, is_active, is_default, sort_order) VALUES
    ('default',    '默认/通用',  'DEF', '{{}}',                    true, true,  0),
    ('atapu',      '阿塔普',     'ATP', '{{Atapu}}',               true, false, 1),
    ('bozhong_25_1','渤中25-1',  'BZ5', '{{渤中25-1混}}',         true, false, 2),
    ('caofeidian', '曹妃甸',     'CFD', '{{}}',                    true, false, 3),
    ('panyu',      '番禺',       'PAY', '{{}}',                    true, false, 4),
    ('jinzhou_9_3','锦州9-3',    'JZ9', '{{锦州9-3原油}}',         true, false, 5),
    ('liuhua',     '流花',       'LIH', '{{}}',                    true, false, 6),
    ('luda_10_1',  '旅大10-1',   'LD0', '{{旅大}}',               true, false, 7),
    ('nanpu',      '南堡',       'NAB', '{{南堡35-2}}',            true, false, 8),
    ('qinhuangdao','秦皇岛',     'QHD', '{{秦皇岛32-6,秦皇岛32-6CN}}', true, false, 9),
    ('xinxi_jiang','新西江',     'XJN', '{{}}',                    true, false, 10)
ON CONFLICT (crude_type_id) DO NOTHING;
"""


def init_db():
    """幂等建 schema 与 8 张业务表。应用启动时调用一次。

    与主项目一致：用裸 SQL DDL + CREATE TABLE IF NOT EXISTS，不引入 alembic。
    """
    with engine.begin() as conn:
        for stmt in _DDL.split(';'):
            s = stmt.strip()
            if s:
                conn.execute(text(s))
        # 增量迁移：为已存在的 devices 表补 enabled 列
        _migrate(conn)
    return True


def _migrate(conn):
    """增量列迁移：安全地添加新列（IF NOT EXISTS 语义）。"""
    # PostgreSQL 14+ 支持 ADD COLUMN IF NOT EXISTS
    conn.execute(text(
        "ALTER TABLE {schema}.devices "
        "ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE".format(schema=DB_SCHEMA)
    ))
    # material_flows 增加 target_product_id（tank_to_target/direct 的目标装置进料产品）
    conn.execute(text(
        "ALTER TABLE {schema}.material_flows "
        "ADD COLUMN IF NOT EXISTS target_product_id VARCHAR(64)".format(schema=DB_SCHEMA)
    ))


if __name__ == "__main__":  # 手动初始化：python -m calc_service.backend.data.db
    init_db()
    print(f"[init_db] schema={DB_SCHEMA} 表已就绪")
