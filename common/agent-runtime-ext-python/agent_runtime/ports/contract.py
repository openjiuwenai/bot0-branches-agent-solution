# coding: utf-8

"""协议满足性的判定核心（ports 层，所有层可依赖）。

## 为什么在 ports 而不在 bootstrap

判定「一个对象满不满足某个端口协议」是**关于端口的工具**，它的消费方分布在三层：
装配根（`bootstrap/a2a_app.py` 的能力位）、入站适配器（`adapters/inbound/a2a/push_receiver.py`
的回灌通道判定）、应用层（`application/active_streams.py` 的中断通知判定）。
放在 bootstrap 会让后两者反向依赖最外层——架构守门器实测拦下过这一形态。

本模块只用标准库（`inspect`／`typing`／`dataclasses`），无任何框架依赖，
满足 `.importlinter` 的 `inner-no-frameworks` 契约。

## 两个出口，一份判定

- `satisfies(value, protocol) -> bool`：能力判定用，不满足返回假
- `bootstrap/contract_check.py` 的 `require(...)`：装配期用，不满足即抛并给出可定位说明

**判定逻辑只有一份**——两份会各自演进，于是「装配期收下了」与「能力位认可了」
可能给出不同答案。

## 为什么不能用 `isinstance`

`@runtime_checkable` 协议的 `isinstance` **只查成员存不存在**，不看它是不是方法、
签名对不对。实测两例都为真：

    class BogusRunner:  claim_callback = "我不是方法，只是个字符串"
    isinstance(BogusRunner(), CallbackBackfiller)   -> True

    class BogusSink:
        can_backfill_callback = True
        def accept_push_callback(self): return True   # 少两个参数
    isinstance(BogusSink(), CallbackSink)            -> True

而这类布尔位驱动的是 Agent Card 的 `pushNotifications` 能力位与回调接收入口——
「对客户端声称支持完成回调」这件事，其判据不该是「有没有一个叫这个名字的属性」。
端口 `agent_runtime/ports/callback.py` 的 `CallbackSink` 自己写着「**由类型回答，不由探测回答**」。
"""
from __future__ import annotations

from typing import Any, Optional


def _members_of(protocol: type) -> list[str]:
    """协议要求的成员名，用于错误信息。

    **不读解释器的内部属性**：那个属性在 3.12 才有，3.11 下取到空——错误信息会变成
    「契约要求：[]」，读的人无从判断少了什么。实测踩过：本机 3.12 读数正常、
    容器 3.11 为空，而两处跑的是同一份代码。

    改按公开语义推导：协议类自己定义的、非私有的成员名（含注解声明的数据成员）。
    """
    names: set[str] = set()
    for klass in getattr(protocol, "__mro__", (protocol,)):
        if klass in (object,) or getattr(klass, "__module__", "") == "typing":
            continue
        names |= {n for n in vars(klass) if not n.startswith("_")}
        names |= {n for n in getattr(klass, "__annotations__", {}) if not n.startswith("_")}
    return sorted(names)


def _provides(value: Any, name: str, protocol: type) -> bool:
    """该成员是实现方**自己提供的**，而非从协议继承来的省略号方法体。

    取静态属性再与协议上的同名对象比对：是同一个对象就说明它来自协议本身。
    用静态取值而非普通取值，是为了不触发描述符与动态代理——判的是「有没有定义」，
    不是「取得到什么」。

    **校验的对象是实例，不是类**：数据成员常在构造期赋值（`self.agent_id = ...`），
    按类查会判成缺失、按实例查才准。本仓六个处理器实现正是这个形态——
    拿类去扫会得到六处假阳性。

    **动态代理（以 `__getattr__` 提供成员）会被判为未提供，这是有意的**：静态取值
    看不见它，而这正是我们要的——那类实现的方法面在运行前不可知，装配期无从校验，
    放行等于把校验变成看运气。它跨解释器版本的行为也不一致（曾实测 3.11 通过、
    3.12 被拒）。**替代路径**：在类上显式声明要暴露的方法（哪怕方法体只是转发给
    代理），装配即通过——显式声明本身就是这类实现该付的代价。
    """
    import inspect as _inspect

    try:
        got = _inspect.getattr_static(value, name)
    except AttributeError:
        return False
    return got is not getattr(protocol, name, object())


def _signature_mismatch(value: Any, name: str, protocol: type) -> Optional[str]:
    """该成员的签名与协议是否兼容；兼容返回空，否则返回一句可定位的说明。

    **只比三件事**：位置参数个数、关键字参数名集合、是不是协程。
    参数改名不算不符——位置调用不受影响，强求同名会拒掉正当的实现。
    类型标注也不比——协议的标注是意图，实现方可以写得更宽。

    这三件事各自对应一种实测到的静默失效：参数个数不对（调用当场抛，异常被上层吞）、
    关键字写成位置（同上）、方法不是协程（返回一个协程对象、副作用从不发生，
    而请求照样成功返回）。
    """
    import inspect as _inspect

    expected = getattr(protocol, name, None)
    if not callable(expected):
        return None
    try:
        got = _inspect.getattr_static(value, name)
    except AttributeError:
        return None
    got = getattr(got, "__func__", got)
    if not callable(got):
        # **协议要求它可调用，而实现方给的不是**——此前这里返回「无问题」，
        # 于是用一个字符串冒充方法能同时骗过 `require()` 与 `isinstance()`：
        #
        #     class BogusRunner:  claim_callback = "我不是方法，只是个字符串"
        #     isinstance(BogusRunner(), CallbackBackfiller)   -> True（修前）
        #
        # 这不是「签名不同」，是根本不能调用——调用它当场抛属性错误或类型错误，
        # 而那是装配期这道门本该拦住的第一类形态。
        return (
            f"`{name}` 不是可调用对象（实得 {type(got).__name__}），而协议要求它是——"
            "调用它会当场抛，且异常多半被上层吞成一个与真实原因无关的内部错误"
        )
    try:
        want_sig = _inspect.signature(expected)
        have_sig = _inspect.signature(got)
    except (TypeError, ValueError):
        return None

    # **判据是「能不能被协议的调用形态调用」，不是「签名长得一不一样」**。
    # 比得比协议还严会拒掉合法实现——实测误拒过四种：多一个带默认值的关键字参数、
    # 多一个带默认值的位置参数、构造期把协程赋成实例属性（闭包式）、静态方法。
    # 它们都能被正常调用，端口文档也明写允许鸭型满足。
    bound = getattr(_inspect.getattr_static(value, name), "__get__", None) is not None
    try:
        probe = have_sig
        if bound and "self" in probe.parameters:
            probe = probe.replace(
                parameters=[x for n, x in probe.parameters.items() if n != "self"]
            )
        want = want_sig.replace(
            parameters=[x for n, x in want_sig.parameters.items() if n != "self"]
        )
        probe.bind(*[None] * len([
            x for x in want.parameters.values()
            if x.kind in (x.POSITIONAL_ONLY, x.POSITIONAL_OR_KEYWORD)
        ]), **{
            n: None for n, x in want.parameters.items() if x.kind is x.KEYWORD_ONLY
        })
    except TypeError as exc:
        return f"`{name}` 无法按协议的调用形态调用——{exc}（协议签名 {want_sig}）"

    if _inspect.iscoroutinefunction(expected) and not _inspect.iscoroutinefunction(got):
        return (
            f"`{name}` 不是协程，而协议要求它是——调用它只会拿到一个协程对象，"
            "副作用从不发生，而调用方看到的是「成功返回」"
        )
    return None


class ContractViolation(TypeError):
    """注入物不满足声明的契约。

    继承自类型错误：它就是一次类型不符，只是发生在运行期。
    """


def satisfies(value: Optional[Any], protocol: type) -> bool:
    """注入物是否满足协议——**布尔版，供能力判定使用**。

    未注入（`None`）返回假：没有这个件就没有这个能力。这与 `require` 视 `None` 为
    「不违约」是两回事——那里问的是「给的东西对不对」，这里问的是「有没有这个能力」。

    ## 为什么不返回 `TypeGuard`

    `TypeGuard[_P]` 需要把参数标成 `type[_P]`，而静态检查不允许把 `Protocol` 类
    传给 `type[X]`（`type-abstract`）——实测改完当场多出四处新错误，且只能靠全局
    放宽那条检查来压。**收窄由调用点用 `cast` 局部完成**，代价只在那几处。
    """
    if value is None or isinstance(value, type):
        return False
    expected = _members_of(protocol)
    if any(not _provides(value, name, protocol) for name in expected):
        return False
    return not any(
        _signature_mismatch(value, name, protocol) is not None for name in expected
    )


__all__ = ["satisfies"]
