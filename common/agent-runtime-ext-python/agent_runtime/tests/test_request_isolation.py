# coding: utf-8

"""执行请求不共享可变状态的判据（状态外置：每请求独立）。

## 这组判据防的是什么

根设计 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/L2-overview.md` §9.2 的横切并发不变量是
「每请求独立 ctx、**不共享可变状态**」，`agent_runtime/domain/context.py` 的类文档
也复述了这句。而 `@dataclass(frozen=True)` **只挡住给字段重新赋值，不挡容器内改**——
那句话此前靠约定成立。实测两条真实路径：

    request = ServeRequest(messages=[...], metadata={"k": 1})
    request.metadata["k"] = 999      # 成功，frozen 拦不住

    lst = [...]; request = ServeRequest(messages=lst)
    lst.append(...)                  # 构造方还握着那个对象，改到了请求里

第三条更实际：`for_resume` 在无补充文本时写作 `messages = self.messages`——
**续接请求与原请求共享同一个列表对象**。

## 为什么这是「做进类型」而不是「加一条注释」

同仓 `agent_runtime/ports/secret.py:7-20` 对同类问题的判词是：
「原先靠字段注释约束调用方，**约定挡不住四条真实路径**，故把掩码做进类型」。
那一处做了，这一处此前没做。

## 每条判据怎样会失败

- 构造期不再复制 → 第一、二、三条同时红（那是唯一一道防线，见第三条的说明）
- 派生请求的 metadata 与父请求共享 → 第四条红
"""
from __future__ import annotations

from agent_runtime.domain.context import ServeRequest


def test_the_caller_cannot_mutate_a_request_after_constructing_it() -> None:
    """构造方在构造之后改自己那份列表，改不到请求里。

    `ServeRequest(messages=lst)` 之后 `lst.append(...)`——构造方还握着那个对象。
    这是「不共享可变状态」最容易漏掉的一条路径：它不需要任何人「就地改请求」，
    只需要有人留着构造时用的那个容器。

    **这条判据能失败**：把构造期的复制去掉立刻转红。
    """
    messages = [{"role": "user", "content": "第一轮"}]
    metadata = {"trace_id": "tr-1"}

    request = ServeRequest(messages=messages, metadata=metadata)
    messages.append({"role": "user", "content": "构造后注入"})
    metadata["trace_id"] = "被改了"

    assert len(request.messages) == 1
    assert request.metadata["trace_id"] == "tr-1"


def test_two_requests_built_from_the_same_containers_are_independent() -> None:
    """用同一批容器构造两个请求，两者互不影响。

    这是上一条的对偶：**共享的另一头**。构造期不复制时，两个请求指向同一个列表，
    一个请求的派生动作会被另一个看见。

    **这条判据能失败**：把构造期的复制去掉立刻转红。
    """
    shared = [{"role": "user", "content": "共用的一条"}]

    first = ServeRequest(messages=shared)
    second = ServeRequest(messages=shared)

    assert first.messages is not second.messages
    first.messages.append({"role": "user", "content": "只属于第一个"})
    assert len(second.messages) == 1


def test_resume_request_does_not_share_the_message_list() -> None:
    """续接请求与父请求**不共享**消息列表——即使没有补充文本。

    此前两者是同一个对象。**切断它的是构造期的复制**（`__post_init__`），
    不是 `for_resume` 自己——那里曾加过一道 `list(...)`，变异验证读出它是冗余的：
    删掉时本条一条不红，因为构造期那道已经兜住。冗余的防线已随之移除，
    本类的「不共享」现在只有构造期这一道，本条锚的就是它。

    **这条判据能失败**：把构造期的复制去掉立刻转红。
    """
    parent = ServeRequest(messages=[{"role": "user", "content": "第一轮"}])

    child = parent.for_resume(recovery_point_id="anchor-1")

    assert child.messages is not parent.messages
    child.messages.append({"role": "user", "content": "只属于续接请求"})
    assert len(parent.messages) == 1


def test_resume_request_does_not_share_metadata_either() -> None:
    """续接请求的 metadata 也不与父请求共享。

    续接标记就写在 metadata 上——共享意味着**父请求会突然变成一个续接请求**。

    **这条判据能失败**：让 `for_resume` 复用父请求的 metadata 对象立刻转红。
    """
    parent = ServeRequest(messages=[{"role": "user", "content": "问"}], metadata={"k": 1})

    child = parent.for_resume(recovery_point_id="anchor-1")

    assert child.metadata is not parent.metadata
    assert child.is_resume is True
    assert parent.is_resume is False, "父请求被派生动作改成了续接请求"
