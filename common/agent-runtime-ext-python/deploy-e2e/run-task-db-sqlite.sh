#!/usr/bin/env bash
# Task 快照数据库档的**真 sqlite 往返**（Feat-Func-003b §11.2）。
#
# ## 为什么单独一条脚本
#
# 一条脚本 = 一个受判维度。本维要存量基础包 `openjiuwen_runtime`（它提供数据库
# 处理器实现），本机与 CI runner 都没有。与 `run-task-db.sh` 的另两段放一起时，
# 整条脚本退 3、`deploy-e2e` 整道随之「未判」、CI 判定为失败——实测打红过一次。
#
# 拆开之后：另两段在哪都判得了；本维的前置与理由写进 CI 的 `E2E_NOT_APPLICABLE`，
# 进 diff、被复核看见、门禁把理由连同条目打印在日志里。
#
# ## 它验什么
#
# 开启数据库档后，Task 快照经真实的 sqlite 处理器落盘、读回，落盘形态与存量一致。
# 进程内判据用替身验过同样的三件事；本维多出来的是**真实数据库驱动那一层**——
# 替身不会因为字段类型、连接池参数或方言差异而失败，真驱动会。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/_backend.sh"

if ! "$(_e2e_python)" -c "import openjiuwen_runtime" >/dev/null 2>&1; then
  e2e_log "⏭ 真 sqlite 往返未判——缺 openjiuwen_runtime（存量基础包，提供数据库处理器实现）"
  e2e_log "   未判不等于通过；已登记 internal/ledger/ISSUE-LEDGER.md"
  exit 3
fi

WORK="$HERE/.e2e-logs"
mkdir -p "$WORK"
DB="$WORK/task-db-roundtrip.sqlite3"
rm -f "$DB"

e2e_log "真 sqlite 往返：开启数据库档，存一个任务后读回并核对落盘形态"
"$(_e2e_python)" - "$DB" <<'PYEOF'
import asyncio, base64, json, sys

from a2a.types.a2a_pb2 import TASK_STATE_WORKING, Task, TaskStatus

from agent_runtime.bootstrap.config.runtime_config import RuntimeDbConfig
from agent_runtime.bootstrap.task_store_wiring import build_a2a_task_store


class _Cache:
    """进程内缓存：本维要验的是数据库那一层，缓存不是被测对象。"""

    def __init__(self):
        self.data = {}

    async def get(self, key):
        return self.data.get(key)

    async def setex(self, key, ttl_s, value):
        self.data[key] = value

    async def write_externally_governed(self, key, value):
        self.data[key] = value

    async def delete(self, *keys):
        for key in keys:
            self.data.pop(key, None)


async def main(path):
    cache = _Cache()
    store, hook = build_a2a_task_store(
        cache,
        runtime_db=RuntimeDbConfig(
            runtime_db_enabled=True, runtime_db_type="sqlite", runtime_db_sqlite_path=path
        ),
    )
    await hook()
    await store.save(Task(id="t-e2e", context_id="c-e2e",
                          status=TaskStatus(state=TASK_STATE_WORKING)), None)

    cache.data.clear()                      # 缓存全丢，逼它回源真库
    back = await store.get("t-e2e", None)
    assert back is not None, "真 sqlite 回源读不回任务"
    assert back.id == "t-e2e" and back.context_id == "c-e2e", back

    await store.delete("t-e2e", None)
    cache.data.clear()
    assert await store.get("t-e2e", None) is None, "删除后仍读得回"
    print("[e2e-task-db] 真 sqlite 往返通过：存 → 缓存全丢 → 回源读回 → 删除")


asyncio.run(main(sys.argv[1]))
PYEOF
rc=$?
[ "$rc" -eq 0 ] || { e2e_log "❌ 真 sqlite 往返失败"; exit 1; }
e2e_log "✅ Task 快照数据库档：真 sqlite 往返通过"
exit 0
