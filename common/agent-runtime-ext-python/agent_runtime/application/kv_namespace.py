# coding: utf-8

"""命名空间化的键值门面：把状态存储收窄到一个命名空间内。

消费方拿到的是「这个命名空间里的 put/get/delete」，不必每次传命名空间，
也就不会把别的命名空间的键写进来。默认过期时间在构造时定，
每次写入重设——过期由写入驱动，不单独续期。
"""
from __future__ import annotations

from typing import Any, Optional

from agent_runtime.ports.state_store import StateStorePort

#: 默认过期秒数，与存量一致。
DEFAULT_TTL_SECONDS = 1800


class KvNamespace:
    """一个命名空间内的键值读写。"""

    def __init__(
        self,
        data_store: StateStorePort,
        *,
        namespace: str,
        default_ttl_seconds: int = DEFAULT_TTL_SECONDS,
    ) -> None:
        self._store = data_store
        self._namespace = namespace
        self._default_ttl_seconds = default_ttl_seconds

    @property
    def namespace(self) -> str:
        return self._namespace

    async def put(
        self,
        key: str,
        value: dict[str, Any],
        *,
        ttl_seconds: Optional[int] = None,
        metadata: Optional[dict[str, Any]] = None,
    ) -> None:
        await self._store.write(
            self._namespace,
            key,
            value,
            ttl_seconds=self._default_ttl_seconds if ttl_seconds is None else ttl_seconds,
            metadata=metadata,
        )

    async def get(self, key: str) -> Optional[dict[str, Any]]:
        record = await self._store.read(self._namespace, key)
        return None if record is None else record.value

    async def delete(self, key: str) -> None:
        await self._store.remove(self._namespace, key)
