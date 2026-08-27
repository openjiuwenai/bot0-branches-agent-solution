# coding: utf-8

"""错误面、Redis 键 schema 与 SSE 帧格式快照（M0.5 / M0.7 / M0.8）。

内联于 dispatch 请求处理路径中的形态（429 拒绝体、415 响应、cancel 响应、
SSE 帧格式）无法脱离完整路由调用，对其做**源面断言**：断言生产源码中的
冻结形态字面存在。源面断言是刻意的强耦合——这些形态就是冻结面本身，
对它们的任何改写都应当被本套件拦下并升级为产品决策。
"""

from __future__ import annotations

import inspect
import re

import api.dispatch as dispatch_module
import orchestrator.heartbeat_runtime as heartbeat_module
from oracle_support.regression_baseline import frozen_facts as ff
from orchestrator.state.task_state_manager import CONV_TASK_KEY

import common.logger as logger_module
from common.constants import session_request_key
from common.redis_task_store import _KEY_PREFIX as TASK_KEY_PREFIX

_DISPATCH_SRC = inspect.getsource(dispatch_module)


# ── 限流与错误码 ────────────────────────────────────────────────────────


def test_rate_limit_constants_match_frozen():
    assert dispatch_module.ERROR_CODE_RATE_LIMIT_EXCEEDED == ff.ERROR_CODE_RATE_LIMIT
    assert dispatch_module.ERROR_MSG_RATE_LIMIT_EXCEEDED == ff.ERROR_MSG_RATE_LIMIT


def test_rate_limit_rejection_shape_frozen_in_source():
    """429 拒绝体 5 字段、构造顺序逐字面冻结（dispatch.py 的 _dispatch_body）。

    注意该形态与 wrap_error 不同：键名 error 非 error_msg、无 execution_time。
    """
    pattern = re.compile(
        r"rejection\s*=\s*\{\s*"
        r'"success":\s*False,\s*'
        r'"error":\s*error_msg,\s*'
        r'"error_code":\s*error_code,\s*'
        r'"conversation_id":\s*conversation_id,\s*'
        r'"agent_id":\s*agent_id,\s*\}',
    )
    assert pattern.search(_DISPATCH_SRC), "429 拒绝体构造形态偏离冻结面"


def test_unsupported_media_type_shape_frozen_in_source():
    """415 响应三字段形态（dispatch.py 的 dispatch）。"""
    pattern = re.compile(
        r'\{\s*"success":\s*False,\s*'
        r'"error":\s*"unsupported_media_type",\s*'
        r'"message":\s*"[^"]+",\s*\}',
    )
    assert pattern.search(_DISPATCH_SRC), "415 响应形态偏离冻结面"


def test_cancel_response_frozen_in_source():
    """cancel 成功响应 {"status": "cancel_requested"}（dispatch.py 的 cancel_task）。"""
    assert 'JSONResponse({"status": "cancel_requested"})' in _DISPATCH_SRC


# ── SSE 帧格式 ──────────────────────────────────────────────────────────


def test_sse_frame_format_frozen_in_source():
    """标准出流帧 data: <payload>\\n\\n（dispatch.py 的 probe_generate 与 limited_generate）。"""
    assert r'yield f"data: {payload}\n\n"' in _DISPATCH_SRC
    assert r'yield f"data: {json.dumps(probe_response, ensure_ascii=False)}\n\n"' in _DISPATCH_SRC


def test_redis_key_schema_frozen():
    assert TASK_KEY_PREFIX == ff.KEY_TASK_PREFIX
    assert CONV_TASK_KEY == ff.KEY_CONV_TASK_TEMPLATE
    assert session_request_key("c-1") == ff.KEY_REQUEST_TEMPLATE.format("c-1")

    heartbeat_src = inspect.getsource(heartbeat_module)
    assert 'f"session:{request_id}:heartbeat_seq"' in heartbeat_src


def test_conv_task_key_renders_expected():
    assert CONV_TASK_KEY.format("c-1") == "session:c-1:a2a_task_id"


# ── 身份 header 约定 ────────────────────────────────────────────────────


def test_identity_headers_frozen_in_source():
    logger_src = inspect.getsource(logger_module)
    for header in (ff.HEADER_USER_ID, ff.HEADER_USER_ID_FALLBACK, ff.HEADER_CUST_TOKEN):
        assert header in logger_src, f"身份 header {header} 从来源模块消失"
