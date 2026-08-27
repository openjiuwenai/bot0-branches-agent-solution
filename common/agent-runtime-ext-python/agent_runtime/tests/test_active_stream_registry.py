# coding: utf-8
# 判据按上游 ext-java 的同包语义组织：测试与被测件在 Java 侧同包，
# 读内部状态不构成受保护访问；fixture 参数同名是 pytest 的注入约定。
# pylint: disable=protected-access


"""在途流登记表（Feat-Func-000b「注销幂等」，原登记为待建）。

## 这张表撑着两件事

| 用途 | 表不准的后果 |
|---|---|
| 关停排水 | 句柄泄漏则排水永远等不到零，关停被拖满整个宽限期才强停 |
| 会话级取消 | 摘错句柄则取消打到别的流上，被取消的那条继续跑 |

## 注销为什么必须幂等

收尾路径可能被走两次——正常结束一次、异常清理再一次。要求调用方保证只注销一次，
等于把「注销必须无条件执行」和「注销只能执行一次」两个相反的约束同时压给它：
无条件执行意味着异常路径也要走，而异常路径无从知道正常路径是否已经走过。
"""
from __future__ import annotations

from agent_runtime.application.active_streams import ActiveStreamRegistry, StreamHandle
from agent_runtime.ports.interrupt import InterruptReason


def test_unregister_twice_is_a_no_op():
    """对同一句柄重复注销是空操作，不抛出。

    **这条判据能失败**：注销改为直接移除并在缺失时抛出即转红。
    那种实现会让异常清理路径在正常路径已注销后炸掉，把一次正常收尾变成故障。
    """
    registry = ActiveStreamRegistry()
    handle = registry.register("conv-1")
    registry.unregister(handle)
    registry.unregister(handle)  # 不得抛出
    assert registry.cancel("conv-1") == 0, "注销后该会话应无在途流"


def test_unregister_unknown_handle_is_a_no_op():
    """注销一个从未登记过的句柄同样是空操作。

    调用方持有的句柄可能来自已被整体清理的会话，此时注销不该炸。
    """
    registry = ActiveStreamRegistry()
    stray = ActiveStreamRegistry().register("conv-x")
    registry.unregister(stray)  # 不得抛出


def test_unregister_only_removes_its_own_handle():
    """注销**只摘自己**，同会话的兄弟句柄不受影响。

    **这条判据能失败**：注销改为按会话整体清空即转红——
    那时一条流正常结束会把同会话其他在途流一并摘掉，排水随即认为它们已结束，
    而它们其实还在跑。
    """
    registry = ActiveStreamRegistry()
    first = registry.register("conv-2")
    second = registry.register("conv-2")
    registry.unregister(first)
    assert registry.cancel("conv-2") == 1, "兄弟句柄应仍在表内且可被取消"
    registry.unregister(second)
    assert registry.cancel("conv-2") == 0


def test_cancel_unknown_conversation_returns_zero():
    """取消一个不存在的会话返回零，不抛出。

    调用方可能在执行已结束后才发来取消——那是正常时序，不是错误。
    """
    registry = ActiveStreamRegistry()
    assert registry.cancel("never-existed") == 0


def test_cancel_sets_every_handle_of_the_conversation():
    """取消置位该会话下**当时存在的全部**句柄。

    只置位其一会让同会话的其余流继续跑，而调用方已经收到「已取消」。
    """
    registry = ActiveStreamRegistry()
    handles = [registry.register("conv-3") for _ in range(3)]
    assert registry.cancel("conv-3") == 3
    assert all(h.cancelled for h in handles), "全部句柄都应被置位"


def test_cancel_marker_does_not_leak_into_the_next_execution():
    """取消标记**不跨执行残留**：同一会话的第二次执行不受第一次取消的影响。

    **这条要的是跨执行的观察**：单次执行内的取消由句柄置位表达，
    而「第二次还能不能正常跑」取决于新执行是否拿到**新句柄**。
    复用句柄的实现会让一次取消把该会话此后所有执行一并废掉——
    调用方看到的是「取消一次之后这个会话就再也不工作了」。

    **这条判据能失败**：登记改为按会话复用同一句柄即转红。
    """
    registry = ActiveStreamRegistry()

    first = registry.register("conv-5")
    assert registry.cancel("conv-5") == 1
    assert first.cancelled is True
    registry.unregister(first)

    second = registry.register("conv-5")
    assert second.cancelled is False, "新一次执行的句柄不应带着上一次的取消标记"
    assert second is not first, "每次执行须拿到新句柄"


def test_empty_bucket_is_reclaimed():
    """会话下最后一条流注销后，该会话的桶被回收。

    不回收则表随会话数单调增长——长运行进程里这是一处不回落的内存占用。
    """
    registry = ActiveStreamRegistry()
    handle = registry.register("conv-4")
    registry.unregister(handle)
    assert "conv-4" not in registry._active, "空桶应被回收"  # noqa: SLF001


def test_the_first_cancel_reason_wins_over_later_ones() -> None:
    """已取消的句柄**保留先到的理由**，后到的不改写它。

    入口层据理由判「本轮该不该落终态」：调用方主动取消时客户端在等一个明确答复、
    本轮须落取消终态；进程关停时执行是被服务端掐断的，那一轮定性为「未完成」。

    **先到的那个才是真正掐断本轮的原因**。上一版无条件覆写，于是关停排水置的
    `LIFECYCLE_SHUTDOWN` 会被随后到达的用户取消改写成 `USER_REQUEST`——
    一个被服务端掐断的轮次就此按正常结果结算。

    **两个方向都锁**：关停在先、用户在后不得被改写；反过来同样不得。
    只锁一个方向时，把判定写成「只有 LIFECYCLE_SHUTDOWN 不可被覆盖」也能过，
    而那是另一条规则（特定理由优先），不是本条要的（先到者胜）。

    **这条判据能失败**：去掉 `cancel` 里的已置位早返回，两个方向一起转红。
    """
    shutdown_first = StreamHandle("conv-a")
    shutdown_first.cancel(InterruptReason.LIFECYCLE_SHUTDOWN)
    shutdown_first.cancel(InterruptReason.USER_REQUEST)
    assert shutdown_first.reason is InterruptReason.LIFECYCLE_SHUTDOWN, (
        "关停理由被随后到达的用户取消改写了——被服务端掐断的轮次会按正常结果结算"
    )

    user_first = StreamHandle("conv-b")
    user_first.cancel(InterruptReason.USER_REQUEST)
    user_first.cancel(InterruptReason.LIFECYCLE_SHUTDOWN)
    assert user_first.reason is InterruptReason.USER_REQUEST, (
        "用户取消理由被关停改写了——客户端等的那个明确答复会丢"
    )


def test_cancelling_twice_stays_idempotent() -> None:
    """重复取消仍然幂等：标志一旦置位不复位。

    与上一条一起把行为夹住——「保留先到理由」不能是靠「第二次调用整个不生效」
    实现的，取消标志本身必须仍然为真。

    **这条判据能失败**：把早返回写成抛异常或复位标志，本条转红。
    """
    handle = StreamHandle("conv-c")
    handle.cancel(InterruptReason.LIFECYCLE_SHUTDOWN)
    assert handle.cancelled is True

    # **每次调用之后都读一次**：只在最后读的话，一个「先复位再置位」的实现
    # 同样能过——中间那一刻消费循环若正好检查标志，就会以为没被取消。
    # 变异读数逼出了这一条：把早返回改成「复位后返回」时，只读末态的断言全绿。
    for _ in range(3):
        handle.cancel()
        assert handle.cancelled is True, "重复取消过程中标志出现过复位"
        assert handle.reason is InterruptReason.LIFECYCLE_SHUTDOWN, "理由在重复取消中被改写"


def test_a_cancel_arriving_before_registration_is_not_lost() -> None:
    """取消早于在途流注册时**不丢**——注册时补置到新句柄上。

    客户端可能在消息受理之后、本轮在途流注册之前就调取消端点，窗口量级是一次存储
    往返；关停排水同样能触发。此前那次取消打在空登记表上直接返回 0、**不留痕迹**，
    随后注册进来的流看到一张干净的表，入口层据以判断「本轮是否已被取消」的依据为假。

    实测形态（真入口路径探针，九种让出时点里的一种）：旧 Task 落取消态、
    新建 Task 落要求输入态——同一会话下 Task 分叉，会话对外仍显示要求输入。

    **这条判据能失败**：把空登记表那一支改回直接 `return 0`，本条转红。
    """
    registry = ActiveStreamRegistry()
    # **先跑完一轮**：要修的窗口是「上一轮注销之后、下一轮注册之前」。
    # 从未跑过任何轮次的会话不在暂存范围内——那次取消没有对象。
    registry.unregister(registry.register("conv-early"))

    assert registry.cancel("conv-early", InterruptReason.LIFECYCLE_SHUTDOWN) == 0, (
        "登记表为空时置位数应为 0——那是事实，不该假装取消到了某条流"
    )

    handle = registry.register("conv-early")
    assert handle.cancelled is True, "早到的取消没有补置到新注册的句柄上"
    assert handle.reason is InterruptReason.LIFECYCLE_SHUTDOWN, "补置时丢了理由"


def test_a_pending_cancel_is_consumed_once() -> None:
    """暂存的取消**只用一次**——下一轮新请求不被上一轮的取消误伤。

    留着不删会造出比原缺陷更难查的形态：用户发一条全新的消息，系统说这轮被取消了，
    而那次取消发生在上一轮。

    **这条判据能失败**：把消费改成读取不删（`get` 而非 `pop`），本条转红。
    """
    registry = ActiveStreamRegistry()
    registry.unregister(registry.register("conv-once"))   # 先跑过一轮
    registry.cancel("conv-once", InterruptReason.USER_REQUEST)

    first = registry.register("conv-once")
    assert first.cancelled is True, "第一轮该拿到那次取消"
    registry.unregister(first)

    second = registry.register("conv-once")
    assert second.cancelled is False, (
        "第二轮被上一轮的取消误伤了——暂存的取消必须一次性消费"
    )


def test_pending_cancel_does_not_leak_across_conversations() -> None:
    """暂存按会话隔离，不串会话。

    **这条判据能失败**：把暂存写成单值而非按会话索引，本条转红。
    """
    registry = ActiveStreamRegistry()
    registry.cancel("conv-a", InterruptReason.USER_REQUEST)

    other = registry.register("conv-b")
    assert other.cancelled is False, "另一个会话的取消串过来了"


def test_a_stale_pending_cancel_does_not_hit_a_later_request(monkeypatch) -> None:
    """暂存的取消**过了时效窗口就不生效**——它等到的是用户下一次全新的请求。

    一次性消费防的是「同一次取消被用两遍」，防不住这一种：取消打在一个**空闲会话**
    上时同样会进暂存，而那次取消本来就没有对应的在途轮次。

    **这条是实测踩出来的**：对等比对脚本先对某会话调取消端点（那时没有在途轮次），
    随后用同一个会话发流式请求——本版一帧都没产出、存量产出三帧，
    差分当场报「帧数不同：本版 0、存量 3」。**这比原缺陷更坏**：
    原缺陷只在竞态窗口内偶发，这个是必现的。

    **这条判据能失败**：去掉时效判断（暂存无限期有效），本条转红。
    """
    import agent_runtime.application.active_streams as mod

    registry = ActiveStreamRegistry()
    registry.unregister(registry.register("conv-stale"))  # 先跑过一轮
    registry.cancel("conv-stale", InterruptReason.USER_REQUEST)

    # 把时钟推到窗口之外——**不用 sleep**：那会让判据的耗时随窗口值涨，
    # 而窗口值是个可调的取舍量，判据不该被它拖慢。
    later = [0.0]
    real = mod.time.monotonic

    def _advanced() -> float:
        return real() + mod._PENDING_CANCEL_WINDOW_S + 1.0 + later[0]

    monkeypatch.setattr(mod.time, "monotonic", _advanced)

    handle = registry.register("conv-stale")
    assert handle.cancelled is False, (
        "过期的暂存取消误伤了后来的请求——用户发一条全新消息，系统说这轮被取消了"
    )


def test_a_fresh_pending_cancel_still_applies() -> None:
    """窗口之内的暂存照常生效——与上一条一起把行为夹住。

    只锁「过期不生效」时，把窗口设成 0 也能过，而那等于没修原缺陷。

    **这条判据能失败**：把窗口设成 0，本条转红。
    """
    registry = ActiveStreamRegistry()
    registry.unregister(registry.register("conv-fresh"))  # 先跑过一轮
    registry.cancel("conv-fresh", InterruptReason.LIFECYCLE_SHUTDOWN)
    handle = registry.register("conv-fresh")
    assert handle.cancelled is True, "刚暂存的取消没有补置到新句柄上"


def test_a_cancel_on_a_never_seen_conversation_is_not_remembered() -> None:
    """取消打在**从未跑过任何轮次**的会话上时不暂存——那次取消没有对象。

    要修的窗口是「消息受理之后、在途流注册之前」，那意味着这个会话此刻正在被处理。
    一个从未被处理过的会话收到取消，等到的是用户下一次**全新的**请求。

    **这条是实测逼出来的**：只按「一次性消费 + 时效窗口」修时，对等比对脚本
    先对某会话调取消端点（那时没有在途轮次），随后用同一个会话发流式请求——
    本版一帧都没产出、存量产出三帧，差分当场报「帧数不同：本版 0、存量 3」。
    **比原缺陷更坏**：原缺陷只在竞态窗口内偶发，这个是必现的。

    **这条判据能失败**：去掉「有过在途轮次」这条约束，本条转红。
    """
    registry = ActiveStreamRegistry()
    # 不先注册——这个会话从未被处理过。
    registry.cancel("conv-never", InterruptReason.USER_REQUEST)

    handle = registry.register("conv-never")
    assert handle.cancelled is False, (
        "从未跑过轮次的会话上那次取消被记住了——它会误伤用户的下一次全新请求"
    )
