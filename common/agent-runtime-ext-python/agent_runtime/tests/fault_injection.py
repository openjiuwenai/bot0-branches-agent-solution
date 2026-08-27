# coding: utf-8

"""故障注入基座：给测试注入 Redis 故障，用于验证数据视图 §3 Redis DFX 声明。

支撑的故障注入靶子（`L2-resilience-and-fault-injection` §3「故障注入靶子」；
本基座自身的三条设计约束见该文 §4「注入基座」）：
  - FI-1 瞬断：前 N 次调用失败、之后恢复 → 验有界重试+退避后成功
  - FI-2 持续不可用：始终失败 → 验 fail-fast（可诊断错误、不伪成功、不丢状态）
  - FI-3 状态推进写入时不可用：对写操作注入失败 → 验保持原态+可重试错误、绝不错误推进
    （runtime 无 CAS——并发正确性由 a2a-sdk 单写者 per Task + 部署实例亲和保证）

设计：包装任意 redis.asyncio 客户端（真 client 或 fakeredis），在方法级注入
`redis.ConnectionError`/`TimeoutError`。默认注入连接错误（模拟 Redis 不可达）。
仅测试用，不进生产路径。
"""
from __future__ import annotations

from typing import Any, Optional


class InjectedRedisError(Exception):
    """注入的 Redis 故障基类（测试用；生产由 redis.exceptions 抛真错）。"""


def _default_error() -> BaseException:
    # 优先用真实 redis 异常类型，使被测 DFX 的 except 分支按生产同型捕获；缺失则退化。
    try:
        from redis.exceptions import ConnectionError as RedisConnectionError

        return RedisConnectionError("injected: redis unavailable")
    except Exception:  # noqa: BLE001  redis 未装（裸环境）
        return InjectedRedisError("injected: redis unavailable")


class FaultInjectingRedis:
    """包装 redis.asyncio 客户端，按配置在方法级注入故障。

    - fail_times=N：前 N 次命中操作失败（瞬断），之后透传真实客户端（恢复）；
    - fail_always=True：持续注入（持续不可用）；
    - target_ops：仅对这些方法名注入（如 {"set","get"}）；None=全部方法；
    - error：注入的异常实例；默认 redis.ConnectionError。

    统计：`.calls`（命中包装方法总数）、`.injected`（实际注入失败次数），供断言。
    - fail_after_yields=N：**产出 N 项之后**再注入（仅对 async generator 生效）。
      建流成功、迭代到一半远端断开，是与「建流即失败」不同的一档——
      前者已经把 N 项交给了调用方，重扫会重复交付，所以实现里那条路径不重试。
      基座此前只支持建流时抛，于是那条「产出中途失败直接上抛」的分支无从注入、
      长期无判据（由外部缺口评估报告扫出）。

    注：`scan` 走 `scan_iter`（async generator），本包装按生成器语义代理。
    """

    def __init__(
        self,
        inner: Any,
        *,
        fail_times: int = 0,
        fail_always: bool = False,
        fail_after_yields: Optional[int] = None,
        target_ops: Optional[set[str]] = None,
        error: Optional[BaseException] = None,
    ) -> None:
        self._inner = inner
        self._fail_remaining = fail_times
        self._fail_always = fail_always
        self._fail_after_yields = fail_after_yields
        self._target = set(target_ops) if target_ops else None
        self._error = error if error is not None else _default_error()
        self.calls = 0
        self.injected = 0
        #: 产出中途注入时，实际已交付给调用方的项数。判据据此断言「不重扫」——
        #: 重扫会让这个数在第二轮继续增长，而调用方那侧会收到重复的键。
        self.yielded = 0

    def _should_fail(self, op: str) -> bool:
        if self._target is not None and op not in self._target:
            return False
        if self._fail_always:
            return True
        if self._fail_remaining > 0:
            self._fail_remaining -= 1
            return True
        return False

    def recover(self) -> None:
        """手动解除注入（模拟 Redis 恢复），用于瞬断→恢复的分段断言。"""
        self._fail_always = False
        self._fail_remaining = 0

    def __getattr__(self, name: str) -> Any:
        # 仅代理 async 可调用方法并注入；非可调用属性透传。
        attr = getattr(self._inner, name)
        if not callable(attr):
            return attr

        import inspect

        if inspect.isasyncgenfunction(attr):
            # async generator（如 scan_iter）：注入在建流时生效，不能 await 调用结果。
            async def _gen_wrapper(*a: Any, **k: Any) -> Any:
                self.calls += 1
                # 建流阶段注入：调用方一项都没拿到，重试是安全的。
                if self._fail_after_yields is None and self._should_fail(name):
                    self.injected += 1
                    raise self._error
                async for item in attr(*a, **k):
                    # 产出中途注入：**先交付再抛**，让调用方确实拿到了前 N 项。
                    # 顺序反过来（先判后交付）测不到那条路径要防的事——
                    # 它防的正是「已经交付出去的项在重扫时被再交付一遍」。
                    # **判定不走 `_should_fail`**：那条路按 `fail_times` 计次，
                    # 而中途注入的触发条件是「已交付满 N 项」，与次数无关。
                    # 第一版照搬了它，于是 `fail_times=0` 时恒不触发——
                    # 实测 injected=0、五项全交付，判据反而红在「没抛异常」上。
                    # 这里只保留 `target_ops` 那一层过滤。
                    #
                    # **两个条件拆成两个具名判断**：合成一条 if 时是四个布尔表达式，
                    # 读的人要同时在脑子里持住「到量了吗」与「是这个操作吗」两件事，
                    # 而它们是彼此独立的。
                    reached_quota = (
                        self._fail_after_yields is not None
                        and self.yielded >= self._fail_after_yields
                    )
                    on_target = self._target is None or name in self._target
                    if reached_quota and on_target:
                        self.injected += 1
                        raise self._error
                    self.yielded += 1
                    yield item

            return _gen_wrapper

        async def _wrapper(*a: Any, **k: Any) -> Any:
            self.calls += 1
            if self._should_fail(name):
                self.injected += 1
                raise self._error
            return await attr(*a, **k)

        return _wrapper
