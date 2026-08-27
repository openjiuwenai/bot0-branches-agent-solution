# coding: utf-8

"""凭据值类型 ``SecretValue``：让"口令不入日志"从约定变成机制。

## 为什么不是 ``str`` 加一条注释

原先各配置类把解密后的口令存为裸 ``str``，靠字段注释"不入日志"约束调用方。
约定挡不住四条真实路径：

1. ``repr(config)`` / ``print(config)`` —— dataclass 默认 repr 展开全部字段
2. f-string 插值 ``f"连接失败：{config}"``
3. 异常回显 —— 未捕获异常把 locals 里的配置对象打进 traceback
4. 结构化日志把整个配置对象作为一个字段序列化

``field(repr=False)`` 只能挡住第 1、3 条中经 dataclass repr 的部分，挡不住
``f"{config.decrypted_password}"`` 这类**直接取字段再插值**的写法。

本类型把掩码做进 ``__repr__`` 与 ``__str__``：无论经哪条路径字符串化，出来的都是
``SecretValue(***)``。取原值必须显式调用 ``reveal()``——这个方法名会在代码评审和
grep 中显形，而 ``config.password`` 不会。

## 落地判据

Feat-Func-000b §12 的 V10：配置对象字符串化后不得含口令原值，含即判红。
本类型使该判据可被机械验证（见 agent_runtime/tests/test_secret_value.py）。
"""
from __future__ import annotations

from typing import Any

_MASK = "SecretValue(***)"


class SecretValue:
    """包裹敏感字符串，字符串化时恒为掩码。

    不可变、可比较、可作字典键。空值（``SecretValue("")``）视为"未配置"，
    ``bool(SecretValue(""))`` 为假——调用方可直接用 ``if secret:`` 判断是否配了口令，
    无需 reveal 后再判，少一处原值出现在代码里的机会。
    """

    __slots__ = ("_value",)

    # 显式声明：__slots__ 只建描述符，不构成类型信息。缺了这行，静态检查器认为
    # 本类没有 _value 属性，而 __setattr__ 被重写成恒抛异常后它也无法从赋值语句推断。
    _value: str

    def __init__(self, value: str = "") -> None:
        object.__setattr__(self, "_value", value or "")

    def reveal(self) -> str:
        """取出原值。**唯一的取值入口**——出现在这里的调用点即是审计范围。"""
        return self._value

    # —— 一切字符串化路径都走掩码 ——
    #
    # 以下四个 dunder 就地抑制 `add-staticmethod-or-classmethod-decorator`：
    # 它们确实不读实例，但**签名由 Python 语言规定**，标成 staticmethod 会让
    # 后来者以为这是普通工具函数、可以随意改签名，而 `__format__` 的 `spec`
    # 与 `__setattr__` 的 `name`/`value` 是解释器按位置传进来的。
    # 就地抑制而非走审批，与上游 `agent-runtime-ext-java` 的做法一致
    # （该模块有 33 处 `@SuppressWarnings` 形态的就地抑制）。
    def __repr__(self) -> str:  # pylint: disable=add-staticmethod-or-classmethod-decorator
        return _MASK

    def __str__(self) -> str:  # pylint: disable=add-staticmethod-or-classmethod-decorator
        return _MASK

    def __format__(self, spec: str) -> str:  # pylint: disable=add-staticmethod-or-classmethod-decorator
        # 覆盖 f"{secret}"、f"{secret:>20}" 等格式化路径；忽略 spec 以免宽度填充泄露长度。
        return _MASK

    def __bool__(self) -> bool:
        return bool(self._value)

    def __eq__(self, other: Any) -> bool:
        if isinstance(other, SecretValue):
            return self._value == other._value
        return NotImplemented

    def __hash__(self) -> int:
        return hash(self._value)

    def __setattr__(self, name: str, value: Any) -> None:  # pylint: disable=add-staticmethod-or-classmethod-decorator
        raise AttributeError("SecretValue 不可变")

    @classmethod
    def of(cls, value: "str | SecretValue | None") -> "SecretValue":
        """从 str / SecretValue / None 归一构造。配置加载处用它，避免调用方判类型。"""
        if isinstance(value, SecretValue):
            return value
        return cls(value or "")
