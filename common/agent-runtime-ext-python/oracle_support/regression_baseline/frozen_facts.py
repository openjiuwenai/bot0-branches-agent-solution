# coding: utf-8

"""存量定制通道冻结面事实（快照回归基线的唯一比对参照）。

本模块把现役实现的对外可观察形态固化为常量。**禁止 import 任何实现模块**
（common/ channels/ api/ orchestrator/）——夹具与实现解耦，实现漂移时快照
测试必须能失败，而不是跟着实现一起漂。

每条常量注明产品代码出处（基线 SHA 41e33e6 时点）。
"""
from __future__ import annotations

# ── agent event 信封（common/response_wrapper.py::wrap_agent_event）──────
# 外层 7 字段，顺序即 json.dumps 输出顺序（dict 插入序）。
AGENT_EVENT_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "output",
    "error",
    "execution_time",
    "custom_rsp_data",
)
# custom_rsp_data 6 字段（response_wrapper.py 的 wrap_agent_event）。
AGENT_CUSTOM_RSP_DATA_FIELDS = (
    "data",
    "event",
    "content",
    "createdTime",
    "latency",
    "plugin",
)
# display 仅在显式传入（非 None）时追加于 plugin 之后（response_wrapper.py 的 wrap_agent_event）。
AGENT_OPTIONAL_DISPLAY_FIELD = "display"
# error_code（空串）仅对以下事件追加于 custom_rsp_data 之后（response_wrapper.py,116-117）。
EVENTS_WITH_ERROR_CODE = frozenset({"planning_execution_process"})

# ── workflow event 信封（response_wrapper.py::wrap_workflow_event）──────
# 5 字段：无 output / error / error_code。
WORKFLOW_EVENT_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "execution_time",
    "custom_rsp_data",
)
WORKFLOW_CUSTOM_RSP_DATA_FIELDS = ("event", "data")

# ── sub_task event 信封（response_wrapper.py::wrap_sub_task_event）──────
SUB_TASK_EVENT_FIELDS = AGENT_EVENT_FIELDS
SUB_TASK_CUSTOM_RSP_DATA_FIELDS = ("event", "sub_task_path", "node_kind", "data")
SUB_TASK_EVENT_TYPE = "sub_task"

# ── 错误信封（response_wrapper.py::wrap_error）───────────────────────────
# 6 字段，无 custom_rsp_data。
ERROR_ENVELOPE_FIELDS = (
    "success",
    "agent_id",
    "conversation_id",
    "execution_time",
    "error_code",
    "error_msg",
)

# ── 限流（api/dispatch.py）────────────────────────────────────────
ERROR_CODE_RATE_LIMIT = "100001"
ERROR_MSG_RATE_LIMIT = "系统超负载，请在稍后重试"
# 429 流式/非流式拒绝体为独立 5 字段形态（dispatch.py 的 _dispatch_body）：
# 注意与 wrap_error 不同——键名为 error 非 error_msg，且无 execution_time。
RATE_LIMIT_REJECTION_FIELDS = (
    "success",
    "error",
    "error_code",
    "conversation_id",
    "agent_id",
)

# ── 其他错误面（api/dispatch.py）────────────────────────────────────────
# 415 Content-Type 校验失败（dispatch.py 的 dispatch）。
UNSUPPORTED_MEDIA_TYPE_FIELDS = ("success", "error", "message")
UNSUPPORTED_MEDIA_TYPE_ERROR = "unsupported_media_type"
# 取消接口成功响应（dispatch.py 的 cancel_task）。
CANCEL_RESPONSE = {"status": "cancel_requested"}

# ── 事件类型族（channels/normalizer.py + mobile_bank_channel.py）────────
# interrupt_start 由 input_required（success=True）与 failed（success=False）
# 两来源产生（mobile_bank_channel.py 的 format_event）。
INTERRUPT_EVENT_TYPE = "interrupt_start"
# normalizer 对无 type 的 artifact 兜底为 thought（normalizer.py 的 _normalize_artifact）。
FALLBACK_EVENT_TYPE = "thought"
# workflow 事件族取值（response_wrapper.py wrap_workflow_event docstring）。
WORKFLOW_EVENT_KINDS = ("message", "end")

# ── Redis 键 schema ─────────────────────────────────────────────────────
# common/redis_task_store.py
KEY_TASK_PREFIX = "a2a:task:"
# orchestrator/state/task_state_manager.py
KEY_CONV_TASK_TEMPLATE = "session:{}:a2a_task_id"
# common/constants.py 的 session_request_key
KEY_REQUEST_TEMPLATE = "session:{}:request"
# orchestrator/heartbeat_runtime.py 的 _next_seq
KEY_HEARTBEAT_SEQ_TEMPLATE = "session:{}:heartbeat_seq"

# ── SSE 帧格式（api/dispatch.py 的 limited_generate、probe_generate；429 流式拒绝帧在 `_dispatch_body` 里同构）─────
# 429 流式拒绝帧原以字面反斜杠字符 \n\n 结尾（非法 SSE 终止），经产品裁决
# （2026-07-21，对齐 Java 版标准 SSE 语义）修复为真实换行终止——全部出流
# 帧自此同构。
SSE_FRAME_PREFIX = "data: "
SSE_FRAME_SUFFIX = "\n\n"

# ── 身份 header 约定（common/logger.py 的 build_http_request_tag_context）─────────────────────────
HEADER_USER_ID = "x-user-id"
HEADER_USER_ID_FALLBACK = "cust-userid"
HEADER_CUST_TOKEN = "cust-token"

# ── 南向出向面 S1：a2a_service → versatile_adapter 的 A2A 报文 ──────────
# 双源同形态：api/dispatch.py::_build_request 与
# channels/mobile_bank_channel.py::build_message。
# DataPart 为 protobuf map，键无序——冻结集合而非顺序。
# headers 键为关键透传行为（issue 2026-04-28：缺失则下游 versatile_adapter
# 收不到 cust-token / x-user-id）。
OUTBOUND_DATA_PART_KEYS = frozenset({"body", "params", "headers"})
OUTBOUND_ROLE_USER = 1
OUTBOUND_TASK_ID = ""

#: 流式响应必带的三个头。逐字取自存量 `api/dispatch.py` 的三处流式响应点
#: （`:943`、`:1031`、`:1248`，三处完全一致）。
#:
#: **`X-Accel-Buffering: no` 是其中最要紧的一个**：它告诉反向代理不要缓冲这条响应。
#: 缺了它，代理会把整段流缓冲到结束再一次性吐出——客户端看到的不是流，
#: 而服务端日志显示逐帧发出，**两侧都看不出问题在哪**。
SSE_RESPONSE_HEADERS = {
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}
