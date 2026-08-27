# coding: utf-8

"""存量 oracle 的按需挂载：本仓不留副本，真值每次从存量仓按锚定提交临时导出。

## 本仓为什么不留副本

曾保留一份 `applications/` 副本作差分真值。实测它被两类污染：我方把枚举基类
改写成 `StrEnum`、把「挂起态独立过期时间」这个我方设计写进了
`common/redis_task_store.py`——而那个文件正被两条差分判据引用。
**差分取的「存量真值」于是不再是存量，判据全绿不再等于与存量一致。**

副本只要可写就会被改。故取消副本，改为用 `tools/legacy_oracle.sh` 按
`tools/authority-pins.toml` 的 `[legacy-baseline]` 提交号临时导出，用后即删。

## 没导出时的行为

依赖 oracle 的判据**整份跳过**，不报导入错误。哪些判据依赖 oracle 由
**扫描 import 自动判定**，不用具名清单——具名清单可以靠「不登记」逃出考核面，
而新加一个 import 了 oracle 的判据不会有人记得回来登记。
"""
from __future__ import annotations

import ast
import sys
from pathlib import Path

#: 存量顶层包名。import 到其中任何一个即判为依赖 oracle。
#:
#: **`tests` 与 `applications` 不可省**：存量的冻结面在 `applications/a2a_service/tests/
#: `tests/` 下，判据可能以 `from tests.x import y` 或
#: `from applications.a2a_service.tests...` 两种写法取用。首版漏了这两个名，
#: 三个判据文件在 oracle 未导出时**收集即失败**而不是被跳过——
#: 「按包名判定」的集合只要不全，逃出去的就不是少数几条，而是整份文件。
_ORACLE_PACKAGES = frozenset({
    "common", "channels", "orchestrator", "agents", "api", "config", "app",
    "applications", "tests", "framework_parallel", "openjiuwen_runtime",
})

_HERE = Path(__file__).resolve().parent
#: 临时导出目录，由 tools/legacy_oracle.sh 生成，已在 .gitignore
_ORACLE = _HERE.parents[1] / ".legacy-oracle" / "applications" / "a2a_service"

#: **挂两处**：判据取用存量符号有两种相对基准——相对 `a2a_service` 根
#: （`from common.x import y`）与相对导出根（`from applications.a2a_service...`）。
#: 只挂其一，另一种写法报 `No module named`，而报的位置在收集期、看不出是挂载不全。
_ORACLE_BASE = _HERE.parents[1] / ".legacy-oracle"
_ORACLE_ROOTS = (
    _ORACLE_BASE,
    _ORACLE,
    #: 存量把数据库与运行时基础库放在这两处，判据经 `openjiuwen_runtime.*` 取用。
    _ORACLE_BASE / "foundation",
    _ORACLE_BASE / "service",
)

for _root in _ORACLE_ROOTS:
    if _root.is_dir() and str(_root) not in sys.path:
        sys.path.append(str(_root))


#: oracle 的路径片段。判据也可能**按路径读**冻结面而不 import 它——
#: 那种写法躲得过 import 扫描，实测漏过一条（按路径读 `frozen_facts.py`）。
#: **只留一个标记**：存量的 `regression_baseline` 与 `framework_parallel` 都在
#: `applications/a2a_service/tests/` 下，前缀已覆盖它们。
#: 把这两个目录名单列会误命中我方同名目录——我方冻结基线迁到
#: `oracle_support/regression_baseline/` 后，读它的判据被当成依赖 oracle 而跳过。
#:
#: **也认 `.legacy-oracle` 这一段**：判据常把路径分段拼
#: （`parents[2] / ".legacy-oracle" / "applications" / "a2a_service"`），
#: 每段是独立字面量，`"applications/a2a_service"` 这个带斜杠的标记一段都匹配不上。
#: 本仓不暴露这个漏判——门禁在跑判据前会先导出存量，`_ORACLE.is_dir()` 为真，
#: `collect_ignore` 整个不生效。**没有导出脚本的环境才暴露**：实测在切分出的上游树上，
#: 三份该跳过的判据照跑并失败。
_ORACLE_PATH_MARKS = ("applications/a2a_service", ".legacy-oracle")


def _depends_on_oracle(path: Path) -> bool:
    """该测试文件是否依赖 oracle。三种形态都要认，少认一种就漏一整份文件。

    | 形态 | 例 | 靠什么认 |
    |---|---|---|
    | import 存量顶层包 | `from common.x import y` | 语法树 |
    | import 存量的测试树 | `from tests.framework_parallel import x` | 语法树 |
    | **按路径读冻结面** | `_ROOT / "applications/a2a_service/.../frozen_facts.py"` | 路径拼接字面量 |

    第三种**只认参与路径拼接的字面量**（`Path` 的 `/` 运算），不认普通字符串。
    理由是实测的：判据里出现 oracle 路径的字符串有两种，机器分不出——
    真引用，与**喂给被测门禁的测试样本**。放宽到所有字面量时，
    9 个只是在样本或说明里提到该路径的判据被误伤，跳过面从 10 涨到 19。
    """
    try:
        tree = ast.parse(path.read_text(encoding="utf-8", errors="replace"))
    except SyntaxError:
        return False
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            if node.level == 0 and node.module and node.module.split(".")[0] in _ORACLE_PACKAGES:
                return True
        elif isinstance(node, ast.Import):
            if any(a.name.split(".")[0] in _ORACLE_PACKAGES for a in node.names):
                return True
        elif isinstance(node, ast.BinOp) and isinstance(node.op, ast.Div):
            # Path 拼接：`base / "applications/a2a_service/..."`
            for side in (node.left, node.right):
                if (
                    isinstance(side, ast.Constant)
                    and isinstance(side.value, str)
                    and any(mark in side.value for mark in _ORACLE_PATH_MARKS)
                ):
                    return True
    return False


#: oracle 未导出时，跳过依赖它的判据文件。
#: **不是静默跳过**：`collect_ignore` 的条目会在 `--collect-only` 下可见，
#: 且交付门禁另有一条判据核对「跳过数与扫描数一致」。
collect_ignore: list[str] = []
if not _ORACLE.is_dir():
    collect_ignore = sorted(
        p.name for p in _HERE.glob("test_*.py") if _depends_on_oracle(p)
    )
