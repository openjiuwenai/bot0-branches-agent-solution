# coding: utf-8

"""RuntimeRedisClient 端口判据（Feat-Func-003b §2.3）。

命令面 = 上游 `spec/spi/RuntimeRedisClient` 的语义类别，经总体设计 §4 三项收窄后：
    get / set / setex / setnx / delete / exists / write_externally_governed / mget / scan

三项收窄及其依据（总体设计 §4，2026-07-30 经授权补入）：
  · 无过期写入 —— 不保留通用形态，只保留一个具名的外部治理写入
  · 不存在则写 —— 采纳并**强制带过期**，签名比上游更严
  · 单独设过期 —— **不提供**。原用于活跃态滑动续期，而续期已改由写入驱动、
    每次写入重设，无消费方（本仓实测：`expire` 曾存在但生产调用点为零）

**不得有 `cas`**：并发正确性由 **a2a-sdk 单写者 per Task + 部署实例亲和**
保证，不做 CAS——原「必须 CAS」经核实偏离 java（java 端口无 cas）、在 protobuf 整值
比较上做不对、且只盖 runtime 一层（agent-core 自有 Redis checkpointer 盖不住）。

裸环境可跑（ports 洋葱内圈，零框架依赖）。
"""
from __future__ import annotations

import agent_runtime.ports.cache as cache_mod
from agent_runtime.ports.cache import RuntimeRedisClient

#: 收窄后的命令面。**这是一份闭集**——多一个少一个都要转红。
#:
#: 九项命令 + 一项生命周期。**释放不是命令，是资源契约**：上游的同名端口继承自
#: 「可自动关闭」（`openJiuwen/agent-runtime-java` 的
#: `spec/spi/RuntimeRedisClient extends AutoCloseable`），释放是契约的一部分。
#: 我方此前缺这一条，包装件靠属性探测决定要不要穿透释放——按端口写出来的实现
#: 没有该方法时，底层连接一次也不会被关，且零日志零信号。
#:
#: 扩这份闭集是破坏性变更，本次的依据是上游端口原文，不是实现方便。
_COMMAND_SURFACE = frozenset({
    "get", "set", "setex", "setnx", "delete", "exists",
    "write_externally_governed", "mget", "scan",
    "aclose",
})


def test_command_surface_is_exactly_the_narrowed_set():
    """命令面恰好是收窄后的九项，不多不少。

    权威 `FEAT-003:61` MUST：须提供消费方需要的**最小**读写、生存期、删除、
    存在性判断、批量读取与扫描语义；`FEAT-003:175` 重申使用方经统一接口完成这六类操作；
    `FEAT-003:42` 要求不同拓扑与适配实现对上层暴露的接口**保持一致**——
    闭集断言正是「一致」的落点：端口一旦能被单方面加方法，一致性就无从谈起。

    **闭集断言而非逐项存在**：只断言「这些方法存在」拦不住有人往端口加方法，
    而端口是稳态与敏态的唯一接触处，加一个方法就是一次破坏性变更。
    总体设计 §4 的三项收窄若被静默撤销，这条转红。

    **它能失败**：加回 `expire` 转红，删掉具名外部治理写入也转红。
    """
    import inspect

    actual = {
        name for name, _ in inspect.getmembers(RuntimeRedisClient)
        if not name.startswith("_")
    }
    extra = actual - _COMMAND_SURFACE
    missing = _COMMAND_SURFACE - actual
    assert not extra, f"端口出现收窄面之外的方法：{sorted(extra)}——扩端口是破坏性变更，须走设计"
    assert not missing, f"收窄面中的方法缺失：{sorted(missing)}"


def test_no_standalone_expire():
    """不提供单独设过期。

    上游端口有该方法（两个重载），我方**有意不提供**：续期已改由写入驱动、
    每次写入重设，无消费方。将来若出现真实消费方，扩端口须走设计。
    """
    assert not hasattr(RuntimeRedisClient, "expire"), (
        "expire 不应存在：总体设计 §4 明写不提供，理由是无消费方"
    )


def test_no_cas_on_port():
    """端口**不提供 cas**——并发正确性靠单写者+亲和，不做 CAS。"""
    assert not hasattr(RuntimeRedisClient, "cas"), (
        "cas 应删除：对齐 java（其端口无 cas）；"
        "protobuf 整值 CAS 不成立且只盖 runtime 一层"
    )


def test_old_port_name_gone():
    """旧名 `CacheStore` 已废（权威名 `RuntimeRedisClient`）。"""
    assert not hasattr(cache_mod, "Cache" + "Store")
    assert hasattr(cache_mod, "RuntimeRedisClient")
