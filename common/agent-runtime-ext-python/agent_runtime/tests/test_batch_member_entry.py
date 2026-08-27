# coding: utf-8

"""批次快照成员条目的类型化判据（依赖倒置 · 对外兼容）。

## 它锁的是什么

批次快照里的成员条目此前是裸 dict，字段名散成五处字符串字面量
（其中 `settled`、`callback`、`parentTaskId` 连常量都没有），读侧全靠
`isinstance(m, dict)` 加 `.get()`。**字段名写错要到运行期才现形**，
而现形的方式是「认领恒假」「回调丢失」这类不产生错误信号的失效。

类型化之后，字段名错在类型检查阶段就报错。

## 两条不可越界

- **落盘形态不变**：`to_dict` 产出的键与顺序与类型化之前逐字一致。
  快照是落盘物，改键名等于改存储形态——旧数据读不回来。
- **读回要容错**：快照里可能混有别的写入方留下的东西，或旧版本的形态。
  一条读不懂的条目应当被跳过，而不是让整次认领失败。

## 期望值来源（不由被测代码推出）

- **落盘形态**取自类型化之前的构造点：三个必有字段
  `toolCallId` / `remoteTaskId` / `settled`，`callback` 条件写入
  （此前的实现是 `if "callback" in previous`）。
- **字段名对齐上游的快照结构**（驼峰而非本仓其余处的下划线），
  故本组判据逐字断言键名，不用常量代入——**常量代入会让改名的变异悄悄通过**。

## 为什么类型定义在领域层

`pyproject.toml` 的 mypy overrides 只对 `agent_runtime.domain.*` 与
`agent_runtime.ports.*` 开 `disallow_untyped_defs`。类型落在领域层才被强制标注
管住；落在适配层等于没做。

**这组判据必须能红**（各条注有失败条件，均已实跑变异）。
"""
from __future__ import annotations

from typing import Any

import pytest

from agent_runtime.domain.remote.delegation import BatchMemberEntry


def test_the_on_disk_shape_is_unchanged() -> None:
    """**落盘形态的键与顺序与类型化之前逐字一致。**

    逐字断言键名而不是用常量代入：常量代入时把常量本身改掉，判据照样绿。

    失败条件（实测）：把 `MEMBER_REMOTE_TASK_ID_FIELD` 改成别的字符串，
    键名对不上，本条转红。
    """
    entry = BatchMemberEntry(tool_call_id="call-1", remote_task_id="remote-9")

    assert list(entry.to_dict().items()) == [
        ("toolCallId", "call-1"),
        ("remoteTaskId", "remote-9"),
        ("settled", False),
    ]


def test_callback_is_written_only_when_it_exists() -> None:
    """**`callback` 只在原本就有时才写。**

    此前的实现是条件写入（`if "callback" in previous`）。无条件写会给从来没有
    回调的成员凭空加一个 `callback: null` 字段——那是落盘形态的改变。

    `has_callback` 与 `callback is None` 是两件事：一个已落定成员的回调载荷
    确实可能是 `None`，那时落盘形态里有 `callback: null`，读回时要能分辨。

    失败条件（实测）：把 `to_dict` 里的 `if self.has_callback:` 改成
    `if self.callback is not None:`，带空回调的条目丢掉那个键，本条转红。
    """
    without = BatchMemberEntry(tool_call_id="c")
    with_payload = BatchMemberEntry(tool_call_id="c", callback={"x": 1}, has_callback=True)
    with_null = BatchMemberEntry(tool_call_id="c", callback=None, has_callback=True)

    assert "callback" not in without.to_dict()
    assert with_payload.to_dict()["callback"] == {"x": 1}
    assert "callback" in with_null.to_dict(), "空回调也要落盘，读回时才分辨得出"
    assert with_null.to_dict()["callback"] is None


def test_a_round_trip_preserves_everything() -> None:
    """**写出去再读回来，条目逐字段相等。**

    失败条件（实测）：`from_dict` 漏读任一字段，往返后不相等，本条转红。
    """
    for entry in (
        BatchMemberEntry(tool_call_id="c1"),
        BatchMemberEntry(tool_call_id="c2", remote_task_id="r2", settled=True),
        BatchMemberEntry(tool_call_id="c3", callback={"digest": "abc"}, has_callback=True),
    ):
        assert BatchMemberEntry.from_dict(entry.to_dict()) == entry


@pytest.mark.parametrize(
    "raw",
    [
        "不是字典",
        123,
        None,
        {},                                  # 没有关联键
        {"toolCallId": ""},                  # 关联键是空串
        {"toolCallId": 42},                  # 关联键不是字符串
        {"remoteTaskId": "r"},               # 只有远端标识，没有关联键
    ],
)
def test_unreadable_entries_are_skipped_not_raised(raw: Any) -> None:
    """**读不懂的条目返回 `None`，不抛。**

    快照里可能混有别的写入方留下的东西，或旧版本的形态。一条读不懂的条目
    让整次认领失败，代价远大于漏掉那一条。

    关联键是**归位键**，没有它这条记录无从对应到任何委托，
    故它缺失或不是非空字符串时整条不可用。

    失败条件（实测）：把 `from_dict` 的 `if not isinstance(raw, dict): return None`
    去掉，非字典入参抛 `AttributeError`，本条转红。
    """
    assert BatchMemberEntry.from_dict(raw) is None


def test_a_missing_remote_task_id_reads_back_as_empty() -> None:
    """**远端标识缺失读回空串，不是 `None`。**

    成员在拿到远端标识之前就被登记，那时这个字段是空串。读回成 `None` 会让
    下游的字符串比对拿到一个非字符串。

    失败条件（实测）：把 `from_dict` 里的 `str(remote_task_id) if ... else ""`
    改成直接 `raw.get(...)`，缺字段时读回 `None`，本条转红。
    """
    entry = BatchMemberEntry.from_dict({"toolCallId": "c"})

    assert entry is not None
    assert entry.remote_task_id == ""


def test_an_empty_remote_task_id_matches_nothing() -> None:
    """**空的远端标识不匹配任何东西。**

    尚未拿到远端标识的成员其字段是空串。用空串去匹配会认领到一个还没发出去的
    成员——回调被安到错误的委托上，而两边都不报错。

    失败条件（实测）：把 `matches_remote_task` 的 `bool(self.remote_task_id) and`
    去掉，空串与空串相等、本条转红。
    """
    pending = BatchMemberEntry(tool_call_id="c")
    assigned = BatchMemberEntry(tool_call_id="c", remote_task_id="r-1")

    assert pending.matches_remote_task("") is False
    assert pending.matches_remote_task("r-1") is False
    assert assigned.matches_remote_task("r-1") is True
    assert assigned.matches_remote_task("r-2") is False


def test_the_settled_flag_is_coerced_to_bool() -> None:
    """**落盘里的真值形态一律收敛成布尔。**

    旧数据或别的写入方可能落一个字符串或数字。读回后它要参与 `if entry.settled`
    这样的判定，形态不一会让「已落定」的判断随写入方而变。

    失败条件（实测）：把 `bool(raw.get(MEMBER_SETTLED_FIELD))` 改成
    `raw.get(MEMBER_SETTLED_FIELD)`，读回的是原值而非布尔，本条转红。
    """
    for raw_value, expected in ((True, True), ("yes", True), (1, True),
                                (False, False), ("", False), (0, False), (None, False)):
        entry = BatchMemberEntry.from_dict(
            {"toolCallId": "c", "settled": raw_value}
        )
        assert entry is not None
        assert entry.settled is expected, f"{raw_value!r} 应收敛为 {expected}"
