# coding: utf-8

"""既有 RedisClient → 统一 CacheStore 契约的适配（T1-1，FEAT-003）。

存量键 schema 与序列化零改动：本适配只换契约面不换行为，供 T1-2 存储
收口接线（RedisTaskStore / session 键消费方 / 框架 checkpointer 注入）
使用。CAS 由服务端 Lua 脚本承载（与实现族同一脚本，语义单源）。
"""
from __future__ import annotations

from typing import List, Optional, Tuple

from common.redis_client import RedisClient

# **不在模块顶层硬 import**：`CAS_LUA_SCRIPT` 来自存量的 `redis-cache` 子包，
# 而该子包**在存量的锚定提交里根本不存在**（顶层只有 applications/docs/foundation/
# management/scripts/service），存量 venv 里只留了一条指向 `runtime/redis-cache`
# 的 editable 安装记录、而那个目录零 git 跟踪、本机不在场。
#
# 这条依赖因此在任何机器上都不可满足。放在顶层时它把整个模块炸成 ImportError，
# 于是 `deploy-e2e/legacy_boot.py` 起不来、`run-a2a-parity.sh` 恒退「未判」——
# **一个可选能力的缺失被放大成整条对等比对判据的静默失效**。
# 该常量只被 `compare_and_set` 一处使用，A2A wire 对等比对完全不碰它。
try:
    from openjiuwen_agent_runtime_redis import CAS_LUA_SCRIPT
except ImportError:                                     # pragma: no cover - 依赖不在场
    CAS_LUA_SCRIPT = None

# T1-2：task-scoped 上下文接缝（Todolist 等消费方使用）。新前缀纯增量，
# payload 对 runtime 不透明（FEAT-003 接缝语义）。
_TASK_SCOPED_PREFIX = "a2a:taskctx:{task_id}:{name}"


def task_scoped_key(task_id: str, name: str) -> str:
    return _TASK_SCOPED_PREFIX.format(task_id=task_id, name=name)


class RedisClientCacheStore:
    """CacheStore 契约实现：包装应用既有 RedisClient（连接生命周期归 app）。"""

    def __init__(self, redis: RedisClient) -> None:
        self._redis = redis

    async def get(self, key: str) -> Optional[str]:
        return await self._redis.get(key)

    async def set(
        self, key: str, value: str, *, ttl_seconds: Optional[int] = None
    ) -> None:
        await self._redis.set(key, value, ex=ttl_seconds)

    async def set_nx(
        self, key: str, value: str, *, ttl_seconds: Optional[int] = None
    ) -> bool:
        return await self._redis.set_nx(key, value, ex=ttl_seconds)

    async def delete(self, *keys: str) -> None:
        if keys:
            await self._redis.delete(*keys)

    async def incr(self, key: str) -> int:
        return await self._redis.incr(key)

    async def expire(self, key: str, ttl_seconds: int) -> bool:
        return await self._redis.expire(key, ttl_seconds)

    async def scan(
        self, cursor: int = 0, *, match: str = "*", count: int = 100
    ) -> Tuple[int, List[str]]:
        next_cursor, keys = await self._redis.client.scan(
            cursor=cursor, match=match, count=count
        )
        return int(next_cursor), list(keys)

    async def compare_and_set(
        self, key: str, expected: Optional[str], new_value: str
    ) -> bool:
        # 到这一步才要求脚本在场：失败面收窄到「真的用了 CAS」，
        # 而不是「导入了这个模块」。
        if CAS_LUA_SCRIPT is None:
            raise RuntimeError(
                "CAS 脚本不在场：存量子包 openjiuwen_agent_runtime_redis 未安装"
                "（其源 runtime/redis-cache 不在存量锚定提交内）。"
                "本适配器的其余契约面不受影响。"
            )
        result = await self._redis.client.eval(
            CAS_LUA_SCRIPT,
            1,
            key,
            expected if expected is not None else "",
            new_value,
            "1" if expected is None else "0",
        )
        return bool(int(result))

    # ── 生命周期：连接归 app 管理，适配器不代管 ─────────────────────────

    @staticmethod
    async def start() -> None:
        return None

    @staticmethod
    async def stop() -> None:
        return None

    async def health(self) -> bool:
        try:
            return bool(await self._redis.client.ping())
        except Exception:
            return False
