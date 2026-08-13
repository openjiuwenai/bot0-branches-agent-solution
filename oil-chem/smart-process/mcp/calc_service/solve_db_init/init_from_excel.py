# -*- coding: utf-8 -*-
"""solve_db 从 Excel 刷新入口（方式 B）。

对齐 README 说明的「方式 B」：包装 calc_service.backend.migrate_excel_to_db，
从 refinery_data.xlsx 重新灌 8 张表（init_db 建表 + TRUNCATE/INSERT + 校验）。

运行（在仓库根目录）：
    python calc_service/solve_db_init/init_from_excel.py

幂等：可重复执行，先 TRUNCATE 再 INSERT。
依赖：sqlalchemy、psycopg2-binary、pandas、openpyxl。
"""
import sys
from pathlib import Path

# 确保能 import calc_service.backend.*（本文件在 calc_service/solve_db_init/ 下，需向上两级到仓库根）
_REPO_ROOT = str(Path(__file__).resolve().parent.parent.parent)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from calc_service.backend.migrate_excel_to_db import migrate, verify  # noqa: E402


def main():
    print("=" * 60)
    print("solve_db 从 Excel 刷新（方式 B）")
    print("=" * 60)
    counts = migrate()
    print("\n[init_from_excel] 各表插入行数：")
    for t, n in counts.items():
        print(f"  {t:30s} {n:4d} 行")

    ok = verify(counts)
    print("\n[init_from_excel] " + ("完成，校验通过 ✅" if ok else "完成，但有校验不通过 ❌ 请检查"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
