# coding: utf-8

"""处理器契约的接入方式与防腐边界（Feat-Func-000b §4.1、§4.3，原登记为待建）。

## 为什么必须是鸭子类型，不能要求继承

处理器由**宿主**实现，它可能已经继承了自己框架的基类。要求继承我方基类等于要求宿主
改造它的类层次，而 Python 不支持多重继承的场景下这直接堵死接入。

契约用协议表达：**实现了全部方法即符合**，与类层次无关。

## 缺方法为什么必须在装配期拒绝

漏实现一个方法，运行期才在调用到它时报属性错误——而那可能发生在生产流量上，
且错误信息只说「对象没有某属性」，不指向「你的处理器没实现契约」。
装配期检查把这个失败提前到启动，且给出可读的原因。

## 防腐边界

领域层与端口层**不得导入任何框架或协议类型**。它由构建期的依赖方向门禁守住；
本模块另加一条运行期断言——门禁靠配置文件生效，配置被改动时判据仍在。
"""
from __future__ import annotations

import ast
import pathlib

import pytest

from agent_runtime.ports.handler import AgentHandler

#: 契约的完整成员面。**从协议对象读出，不手写**——手写的清单会随契约演进而过期，
#: 且过期时判据仍绿：它验的是「符合我记下的那份旧契约」。
# 该属性由协议装饰器在运行期生成，类型信息里没有它——**不能因此改成手写清单**，
# 手写的会随契约演进而过期且过期时判据仍绿。就地标注。
_CONTRACT_MEMBERS = sorted(AgentHandler.__protocol_attrs__)  # type: ignore[attr-defined]


class _DuckTyped:
    """不继承任何基类，但实现了契约的全部成员。"""

    agent_id = "duck"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):  # noqa: ANN001
        return None

    @staticmethod
    async def stream_query(request):  # noqa: ANN001
        yield None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


class _MissingOne:
    """缺一个方法：没有 `stream_query`，其余俱全。"""

    agent_id = "missing"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):  # noqa: ANN001
        return None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


def test_duck_typed_implementation_satisfies_the_contract():
    """不继承任何基类、实现了全部方法的类**符合契约**。

    **这条判据能失败**：契约改用抽象基类并要求继承即转红。
    那种改动会堵死已继承其他框架基类的宿主。
    """
    assert isinstance(_DuckTyped(), AgentHandler), (
        "实现了全部方法的鸭子类型应符合契约，无需继承"
    )


def test_contract_surface_is_read_from_the_protocol_not_hardcoded():
    """契约成员面从协议对象读出，且非空。

    **这条防的是判据自身失效**：把成员面写死在判据里，契约新增成员时判据不会转红——
    它继续验着那份旧契约，而新成员从未被任何东西检查过。
    """
    assert _CONTRACT_MEMBERS, "契约成员面为空，协议可能未标为运行期可检查"
    assert set(_CONTRACT_MEMBERS) <= set(dir(_DuckTyped)), (
        f"判据里的鸭子类型未覆盖契约全部成员：缺 "
        f"{sorted(set(_CONTRACT_MEMBERS) - set(dir(_DuckTyped)))}"
    )


def test_missing_method_does_not_satisfy_the_contract():
    """缺任一方法即**不符合**契约。

    **反面判据，不可省**：只验「鸭子类型可通过」的话，一个恒真的检查同样能通过它——
    那时缺方法的实现会一路装配成功，直到生产流量调到那个方法才报属性错误。

    **这条判据能失败**：契约去掉运行期可检查标记即转红。
    """
    assert not isinstance(_MissingOne(), AgentHandler), (
        "缺 stream_query 的实现不应被判为符合契约"
    )


#: 不得出现在领域层与端口层的模块前缀。
#: **取协议与框架两类**：前者会把 wire 形态漏进领域，后者会把调度与传输设施漏进来。
_FORBIDDEN_IMPORTS = ("a2a", "fastapi", "starlette", "httpx", "redis", "openjiuwen")

#: 受防腐边界约束的层。
#: 不得引入框架与协议类型的内层。
#:
#: **`application` 必须在列**：它是编排层，同样在洋葱的内圈——
#: 洋葱的依赖方向是 adapter → port → application → domain，框架类型止步于 adapter。
#: 上一版只列 `domain` 与 `ports`，实测在 `agent_runtime/application/serve.py`
#: 加一行 `import httpx`，**本判据全绿**，只有 `make arch` 拦得住；
#: 而门禁与判据是两条独立的防线，判据这一条不该有洞。
_INNER_LAYERS = ("domain", "ports", "application")


def _imported_roots(path: pathlib.Path) -> set[str]:
    """取一个模块导入的全部顶层包名。"""
    roots: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(node, ast.Import):
            roots.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            roots.add(node.module.split(".")[0])
    return roots


@pytest.mark.parametrize("layer", _INNER_LAYERS)
def test_inner_layers_import_no_framework_or_protocol(layer: str):
    """领域层与端口层**不导入**任何框架或协议类型。

    构建期的依赖方向门禁守的是同一条边界，本判据是它的运行期复核——
    **门禁靠配置文件生效**，配置被改动或未被执行时，这条仍在。

    **这条判据能失败**：在被约束的层里导入任一被禁包即转红。
    """
    root = pathlib.Path(__file__).resolve().parents[1] / layer
    violations: list[str] = []
    for path in root.rglob("*.py"):
        if "__pycache__" in str(path):
            continue
        hit = _imported_roots(path) & set(_FORBIDDEN_IMPORTS)
        if hit:
            violations.append(f"{path.relative_to(root.parent)} → {sorted(hit)}")
    assert not violations, f"{layer} 层导入了框架或协议类型：{violations}"
