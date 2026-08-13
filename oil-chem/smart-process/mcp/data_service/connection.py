# -*- coding: utf-8 -*-
"""数据库连接管理（data_service 共享层）。

复用主项目 backend/app/db/database.py 的 DATABASE_URL，但通过 connect_args
设 search_path=solve_db,public，使裸表名优先命中 solve_db，同时保留对 public
的访问能力（与 solve_v1/backend/data/db.py 一致）。

KingbaseES 版本 mock 与主项目 database.py、solve_v1 db.py 完全对齐：金仓返回的
非标准版本号会使 SQLAlchemy 解析失败，故固定返回 (8, 6, 0)；纯 PostgreSQL 环境下无副作用。
"""
import os
from contextlib import contextmanager

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

# 绕过 KingbaseES 版本解析报错（金仓返回的非标准版本号会使 SQLAlchemy 解析失败）。
# 对齐主项目 backend/app/db/database.py:17-20 与 solve_v1/backend/data/db.py:26-34。
try:
    from sqlalchemy.dialects.postgresql import base as _pg_base

    def _mock_get_server_version(self, connection):
        return (8, 6, 0)

    _pg_base.PGDialect._get_server_version_info = _mock_get_server_version
except Exception:  # pragma: no cover - 仅在 sqlalchemy 版本异常时跳过
    pass

# 复用主项目 DATABASE_URL（默认指向本地 huilian 库）
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://huilian:huilian2026@localhost:5432/huilian",
)

# search_path=solve_db,public：裸表名优先命中 solve_db，同时保留对 public 的访问能力。
engine = create_engine(
    DATABASE_URL,
    echo=False,
    pool_size=5,
    max_overflow=10,
    connect_args={"options": "-csearch_path=solve_db,public"},
)
SessionLocal = sessionmaker(bind=engine, autocommit=False, autoflush=False)


@contextmanager
def get_session():
    """业务 DB session 上下文管理器。

    用法：
        with get_session() as db:
            rows = device_repo.load_units(db)
            ...
            db.commit()   # 写操作由调用方显式提交
    未显式 commit 即退出时，session 关闭即回滚（autocommit=False）。
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
