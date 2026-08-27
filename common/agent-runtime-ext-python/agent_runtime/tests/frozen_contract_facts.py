# coding: utf-8

"""存量对外形态的冻结清单副本——判据的期望值来源。

## 为什么要有这份副本

判据的期望值必须来自**独立事实源**，不能用被测代码的同一套算法推出来。存量对外形态的
冻结清单原件是 `oracle_support/regression_baseline/frozen_facts.py`——2026-08-22 从旧 fork 的
`applications/a2a_service/tests/regression_baseline/` 迁入本仓；上游存量仓从未有过这份文件。

那份「原件」本身是我方在旧 fork 里对存量代码的**人工转录件**（转录时点的存量提交比锚定提交早 19 个，
2026-08-26 检察官逐条核对与锚定存量一致）；它与存量代码之间的机器判据是同目录的 `test_*_snapshot.py`
（2026-08-26 起进 pytest 收集面）。本文件是它面向判据的**抄件**：只抄判据用到的那几条，并按判据的命名组织。
两条判据分工不同——

- 副本 vs 我方产出：锁住我方不漂移
- 转录件 vs 抄件：锁住抄件不陈旧——转录件在本仓，任何环境都执行、不跳过；比对面是本文件的**全部大写常量**，
  不由 `MIRRORED` 自报（检察官实测：从 `MIRRORED` 摘掉一条即可逃逸，故判据另核 `MIRRORED` 等于全部常量）。
  此前它按旧 fork 的路径找原件，删掉仓内副本后在所有环境里静默跳过了四天，2026-08-26 改回

## 副本会不会变陈旧

会。所以 `test_contract_shape_locked.py` 里有一条专门比对原件与副本的判据；
原件一改，那条立刻转红并指出差异。**副本不是事实源，原件才是**。

## 抄写纪律

本文件的每一条都逐字来自原件，**不得按理解重述**。新增条目时同样从原件复制，
并在下面的对照表登记原件里的常量名——那是比对判据的依据。
"""

from __future__ import annotations

#: 原件路径（相对本仓根）。比对判据据此定位。
FROZEN_SOURCE = "oracle_support/regression_baseline/frozen_facts.py"

#: 智能体事件信封：外层 7 字段，顺序即序列化输出顺序。
AGENT_EVENT_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "output",
    "error",
    "execution_time",
    "custom_rsp_data",
)

#: 智能体事件的 custom_rsp_data：6 字段。
AGENT_CUSTOM_RSP_DATA_FIELDS = (
    "data",
    "event",
    "content",
    "createdTime",
    "latency",
    "plugin",
)

#: display 仅在显式传入（非 None）时追加于 plugin 之后。
AGENT_OPTIONAL_DISPLAY_FIELD = "display"

#: 工作流事件信封：5 字段，无 output / error / error_code。
WORKFLOW_EVENT_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "execution_time",
    "custom_rsp_data",
)

#: 工作流事件的 custom_rsp_data：2 字段。
WORKFLOW_CUSTOM_RSP_DATA_FIELDS = ("event", "data")

#: 子任务事件信封与智能体同构。
SUB_TASK_EVENT_FIELDS = AGENT_EVENT_FIELDS

#: 子任务事件的 custom_rsp_data：4 键。
SUB_TASK_CUSTOM_RSP_DATA_FIELDS = ("event", "sub_task_path", "node_kind", "data")

#: 子任务事件的 event 位取值。
SUB_TASK_EVENT_TYPE = "sub_task"

#: 错误信封：6 字段，无 custom_rsp_data。
ERROR_ENVELOPE_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "execution_time",
    "error_code",
    "error_msg",
)

#: 本副本各常量 → 原件中的同名常量。比对判据逐条核对。
MIRRORED = (
    "AGENT_EVENT_FIELDS",
    "AGENT_CUSTOM_RSP_DATA_FIELDS",
    "AGENT_OPTIONAL_DISPLAY_FIELD",
    "WORKFLOW_EVENT_FIELDS",
    "WORKFLOW_CUSTOM_RSP_DATA_FIELDS",
    "SUB_TASK_EVENT_FIELDS",
    "SUB_TASK_CUSTOM_RSP_DATA_FIELDS",
    "SUB_TASK_EVENT_TYPE",
    "ERROR_ENVELOPE_FIELDS",
)
