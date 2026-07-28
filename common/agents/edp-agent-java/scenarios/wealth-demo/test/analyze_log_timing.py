# -*- coding: utf-8 -*-
"""
EDPAgent 日志耗时分析脚本。

指定 conversation_id，从 edp-agent-java 的 run.log 中提取该会话的：
  (1) 每轮模型调用（LLM）：开始时间、首个Token时间、完成时间、总耗时、输入Token、输出Token
  (2) 工具调用：工具名称、启动时间、完成时间、耗时
  (3) 其他事件（conversation/interrupt/todo 等阶段）的时间区间

日志关键标记（com.huawei.ascend.edp.rail.LogRail / EdpaEventRail）：
  - E2E_MODEL_INPUT           → 模型调用开始（beforeModelCall）
  - [EDPA-DIAG] MODEL_RESPONSE → 模型响应解析完成（含 toolCalls 信息）
  - think_start               → 首个 token 到达（流式输出开始）
  - think_chunk               → 流式 token 块
  - think_end                 → 推理结束
  - E2E_MODEL_OUTPUT          → 模型调用完成（afterModelCall）
  - E2E_MODEL_USAGE           → Token 用量统计
  - [EDPA-DIAG] beforeToolCall → 工具调用开始
  - LogRail: tool call completed, toolName=X → 工具调用完成
  - [EDPA-DIAG] beforeInvoke  → 会话请求开始（conversation_start）
  - ReAct stream iteration N  → LLM 迭代轮次

用法：
    python analyze_log_timing.py <conversation_id> [log_file_path]

示例：
    python analyze_log_timing.py 20260726_191220
    python analyze_log_timing.py 20260726_120239 logs/run/run.log
"""
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional


# ── 默认日志路径 ──────────────────────────────────────────────────────
# run.log 由 logback 的 RUN_LOG FileAppender 直写（<charset>UTF-8</charset>），
# 路径取决于 Java 进程的工作目录（LOG_HOME 默认为 "logs"）：
#   - 从 edp-agent-java/ 启动: edp-agent-java/logs/run/run.log
#   - 从 engine/ 启动:         engine/logs/run/run.log
#
# ⚠️ 如果用 PowerShell `*>` 或 `>` 重定向 stdout 到日志文件，PowerShell 会以
# UTF-16 LE 编码写入，且把 Java 的 UTF-8 输出按 GBK 解码，产生乱码。
# 因此此处自动检测并跳过 UTF-16（含 BOM）的文件，优先使用 logback 直写的 UTF-8 文件。
_EDPA_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
_LOG_CANDIDATES = [
    os.path.join(_EDPA_ROOT, "logs", "run", "run.log"),            # 从 edp-agent-java/ 启动
    os.path.join(_EDPA_ROOT, "engine", "logs", "run", "run.log"),  # 从 engine/ 启动
]


def _is_utf16_bom(path: str) -> bool:
    """检查文件是否以 UTF-16 BOM 开头（即 PowerShell 重定向产生的乱码文件）。"""
    try:
        with open(path, "rb") as f:
            return f.read(2) in (b"\xff\xfe", b"\xfe\xff")
    except Exception:
        return False


# 选择第一个存在且非 UTF-16 的候选路径；若全部为 UTF-16 则回退到第一个候选
DEFAULT_LOG_PATH = next(
    (p for p in _LOG_CANDIDATES if os.path.exists(p) and not _is_utf16_bom(p)),
    _LOG_CANDIDATES[0],
)

# ── 日志行时间戳格式：2026-07-26 12:02:39.203 ───────────────────────
TS_PATTERN = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+"
    r"\[([^\]]*)\]"  # 线程名（含 conversation_id）
)


def parse_ts(ts_str: str) -> datetime:
    """解析日志时间戳字符串为 datetime 对象。"""
    return datetime.strptime(ts_str, "%Y-%m-%d %H:%M:%S.%f")


def ts_diff_ms(start: datetime, end: datetime) -> float:
    """计算两个时间戳的差值（毫秒）。"""
    return (end - start).total_seconds() * 1000


def ts_diff_s(start: datetime, end: datetime) -> float:
    """计算两个时间戳的差值（秒）。"""
    return (end - start).total_seconds()


def fix_garbled_text(text: str) -> str:
    """还原日志中乱码的中文文本。

    Spring Boot 日志输出时存在编码错配：Java 进程用 UTF-8 输出中文字节，
    但被当作 GBK 解码，再以 UTF-16 写入日志文件，导致乱码。

    还原路径：UTF-16 读取得到的字符 -> GBK 编码回字节 -> UTF-8 解码。
    另需过滤 UTF-16 读取不完整字节时产生的私有区域占位字符
    （U+E000~U+F8FF、U+DC80~U+DCFF 等）。

    对于已经是正确中文的文本（如 logback FileAppender 直写的 UTF-8 日志），
    GBK->UTF-8 逆变换会失败，此时保留原文不动。

    Args:
        text: 从日志中读出的可能含乱码的字符串

    Returns:
        还原后的正确中文字符串；若无法还原则返回原文
    """
    if not text:
        return text
    # 先过滤掉 UTF-16 读取产生的私有区域/代理占位字符（无法用 GBK 编码）
    cleaned = "".join(
        ch for ch in text
        if not (0xE000 <= ord(ch) <= 0xF8FF or 0xDC80 <= ord(ch) <= 0xDCFF)
    )
    # 尝试整体还原（适用于纯乱码片段且字节完整的情况）
    try:
        return cleaned.encode("gbk").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        pass
    # 混合文本（含 ASCII 和乱码）或字节不完整时，按字符段处理。
    # 对每个非 ASCII 段，先尝试 GBK->UTF-8 还原；若失败则保留原文
    # （说明是正常中文，非乱码）。
    result_chars: list[str] = []
    buf: list[str] = []
    for ch in cleaned:
        if ord(ch) < 128:
            # ASCII 字符，先刷新 buf
            if buf:
                buf_str = "".join(buf)
                try:
                    result_chars.append(buf_str.encode("gbk").decode("utf-8"))
                except (UnicodeEncodeError, UnicodeDecodeError):
                    # GBK->UTF-8 还原失败，说明是正常中文，保留原文
                    result_chars.append(buf_str)
                buf = []
            result_chars.append(ch)
        else:
            buf.append(ch)
    if buf:
        buf_str = "".join(buf)
        try:
            result_chars.append(buf_str.encode("gbk").decode("utf-8"))
        except (UnicodeEncodeError, UnicodeDecodeError):
            result_chars.append(buf_str)
    return "".join(result_chars)


# ── scriptconfig.yaml 话术映射 ──────────────────────────────────────
# 从 wealth-demo/governance/scriptconfig.yaml 读取 query_intent -> tool_start 映射，
# 用于修正日志中乱码的 tool_start 文案。
_SCRIPTCONFIG_CACHE: Optional[dict] = None


def load_scriptconfig() -> dict:
    """加载 scriptconfig.yaml，返回 query_intent -> tool_start 映射。

    Returns:
        dict: {"query_intent": "tool_start文案", ...}
        若加载失败则返回空 dict。
    """
    global _SCRIPTCONFIG_CACHE
    if _SCRIPTCONFIG_CACHE is not None:
        return _SCRIPTCONFIG_CACHE
    try:
        import yaml
        yaml_path = os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "governance", "scriptconfig.yaml"
        )
        with open(yaml_path, "r", encoding="utf-8") as f:
            config = yaml.safe_load(f)
        mapping = {}
        for intent, scripts in config["scriptconfig"]["query_intent_tool_text"].items():
            mapping[intent] = scripts["tool_start"]
        _SCRIPTCONFIG_CACHE = mapping
        return mapping
    except Exception:
        _SCRIPTCONFIG_CACHE = {}
        return {}


def fuzzy_match_tool_start(fixed_text: str) -> Optional[str]:
    """用模糊匹配从 scriptconfig.yaml 的 tool_start 值中找到最接近的匹配。

    使用 difflib.SequenceMatcher 计算相似度（考虑字符顺序），
    比单纯统计共同字符数更准确。

    Args:
        fixed_text: fix_garbled_text 还原后的 tool_start 文案（可能含乱码）

    Returns:
        匹配的 tool_start 正确值；若无匹配（ratio < 0.5）则返回 None。
    """
    if not fixed_text:
        return None
    mapping = load_scriptconfig()
    if not mapping:
        return None
    import difflib
    best_match = None
    best_ratio = 0.0
    for intent, start in mapping.items():
        ratio = difflib.SequenceMatcher(None, fixed_text, start).ratio()
        if ratio > best_ratio:
            best_ratio = ratio
            best_match = start
    if best_ratio >= 0.5:
        return best_match
    return None


def fuzzy_match_query_intent(fixed_intent: str) -> Optional[str]:
    """用模糊匹配从 scriptconfig.yaml 的 query_intent 键中找到最接近的匹配。

    使用 difflib.SequenceMatcher 计算相似度（考虑字符顺序）。

    Args:
        fixed_intent: fix_garbled_text 还原后的 query_intent（可能含乱码）

    Returns:
        匹配的 query_intent 正确值；若无匹配（ratio < 0.5）则返回 None。
    """
    if not fixed_intent:
        return None
    mapping = load_scriptconfig()
    if not mapping:
        return None
    import difflib
    best_match = None
    best_ratio = 0.0
    for intent in mapping.keys():
        ratio = difflib.SequenceMatcher(None, fixed_intent, intent).ratio()
        if ratio > best_ratio:
            best_ratio = ratio
            best_match = intent
    if best_ratio >= 0.5:
        return best_match
    return None


def tool_start_to_intent(tool_start: str) -> Optional[str]:
    """从 tool_start 文案反推 query_intent。

    当 query_intent 乱码严重无法直接匹配时，可通过 tool_start 文案反推。
    注意：tool_start 到 query_intent 的映射非一一对应（如"理财购买"和
    "理财选品购买"都对应"正在办理理财产品购买..."），此时返回第一个匹配。

    Args:
        tool_start: 正确的 tool_start 文案

    Returns:
        匹配的 query_intent；若无匹配则返回 None。
    """
    if not tool_start:
        return None
    mapping = load_scriptconfig()
    if not mapping:
        return None
    for intent, start in mapping.items():
        if start == tool_start:
            return intent
    return None


@dataclass
class ModelCall:
    """单次 LLM 模型调用。"""
    iter_num: int = 0                          # ReAct 迭代轮次
    start_time: Optional[datetime] = None      # E2E_MODEL_INPUT 时间
    first_token_time: Optional[datetime] = None  # think_start 时间（首个 token）
    end_time: Optional[datetime] = None        # E2E_MODEL_OUTPUT 时间
    usage_time: Optional[datetime] = None      # E2E_MODEL_USAGE 时间
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0
    model_name: str = ""
    finish_reason: str = ""
    tool_names: list = field(default_factory=list)  # 模型决定的工具调用
    think_chunks: int = 0                       # think_chunk 数量

    @property
    def total_duration_ms(self) -> float:
        if self.start_time and self.end_time:
            return ts_diff_ms(self.start_time, self.end_time)
        return 0.0

    @property
    def ttft_ms(self) -> float:
        """Time To First Token（首 token 延迟，毫秒）。"""
        if self.start_time and self.first_token_time:
            return ts_diff_ms(self.start_time, self.first_token_time)
        return 0.0

    @property
    def decode_duration_ms(self) -> float:
        """解码耗时（首 token 到完成，毫秒）。"""
        if self.first_token_time and self.end_time:
            return ts_diff_ms(self.first_token_time, self.end_time)
        return 0.0


@dataclass
class ToolCall:
    """单次工具调用。"""
    tool_name: str = ""
    start_time: Optional[datetime] = None   # beforeToolCall 时间
    end_time: Optional[datetime] = None     # LogRail tool call completed 时间
    start_content: str = ""                 # tool_start 展示文案
    query_intent: str = ""                  # call_versatile 的 query_intent 参数
    query_description: str = ""             # call_versatile 的 query_description 参数
    raw_log: str = ""                       # 原始日志行（证据）

    @property
    def duration_ms(self) -> float:
        if self.start_time and self.end_time:
            return ts_diff_ms(self.start_time, self.end_time)
        return 0.0


@dataclass
class ClientEvent:
    """客户端发射事件。"""
    event_id: str = ""
    content: str = ""
    time: Optional[datetime] = None


# 需要捕获的客户端事件 event_id 集合
CLIENT_EVENT_IDS = frozenset({
    "think_start", "think_chunk", "think_end",
    "todolist_start", "todolist_item", "todolist_end",
    "todo_start", "todo_end", "tool_start", "tool_end",
    "interrupt_start", 
})


@dataclass
class RoundSummary:
    """单轮对话汇总。"""
    round_num: int = 0
    query: str = ""
    round_start: Optional[datetime] = None   # beforeInvoke / conversation_start
    round_end: Optional[datetime] = None      # conversation_end / afterInvoke
    model_calls: list = field(default_factory=list)   # list[ModelCall]
    tool_calls: list = field(default_factory=list)    # list[ToolCall]
    other_events: list = field(default_factory=list)  # [(time, event_desc)]
    client_events: list = field(default_factory=list)  # list[ClientEvent]

    @property
    def total_duration_s(self) -> float:
        if self.round_start and self.round_end:
            return ts_diff_s(self.round_start, self.round_end)
        return 0.0

    @property
    def model_total_ms(self) -> float:
        return sum(m.total_duration_ms for m in self.model_calls)

    @property
    def tool_total_ms(self) -> float:
        return sum(t.duration_ms for t in self.tool_calls)


def extract_conversation_id_from_thread(thread_name: str, conv_id: str) -> bool:
    """判断线程名是否包含指定的 conversation_id。"""
    return conv_id in thread_name


def parse_log(log_path: str, conv_id: str) -> list[RoundSummary]:
    """解析日志文件，按会话轮次分组提取耗时数据。

    策略：
    1. 按 conversation_start（beforeInvoke）分割轮次
    2. 在每轮内按 ReAct iteration 分割模型调用
    3. 匹配 beforeToolCall → tool call completed 为工具调用区间

    Args:
        log_path: 日志文件路径
        conv_id: 会话 ID（如 20260726_191220）

    Returns:
        list[RoundSummary]，每个元素代表一轮对话
    """
    if not os.path.exists(log_path):
        print(f"错误: 日志文件不存在: {log_path}")
        sys.exit(1)

    rounds: list[RoundSummary] = []
    current_round: Optional[RoundSummary] = None
    current_model: Optional[ModelCall] = None
    current_iter: int = 0
    pending_versatile_params: Optional[dict] = None
    pending_todolist_statuses: list[str] = []  # emitTodoEvents 解析出的状态列表

    # 编译正则
    re_model_input = re.compile(r"E2E_MODEL_INPUT messageCount=(\d+), lastMessageType=(\w+)")
    re_model_output = re.compile(r"E2E_MODEL_OUTPUT responseType=(\w+), finishReason=(\w+)")
    re_model_usage = re.compile(
        r"E2E_MODEL_USAGE inputTokens=(\d+), outputTokens=(\d+), totalTokens=(\d+), model=(\S+)"
    )
    re_model_response = re.compile(
        r"\[EDPA-DIAG\] MODEL_RESPONSE finishReason=(\w+), toolCalls=(\d+), toolNames=\[([^\]]*)\]"
    )
    re_before_invoke = re.compile(r"\[EDPA-DIAG\] beforeInvoke sid=(\S+)")
    re_react_iter = re.compile(r"ReAct stream iteration (\d+)/")
    re_before_tool = re.compile(r"\[EDPA-DIAG\] beforeToolCall tool=(\S+) mode=(\S+)")
    re_tool_completed = re.compile(r"LogRail: tool call completed, toolName=(\S+)")
    re_stream_payload = re.compile(r"\[EDPAgent\] stream payload \[(\w+)\]")
    re_conversation_start = re.compile(r"\[EDPAgent\] stream payload \[conversation_start\]")
    re_conversation_end = re.compile(r"\[EDPAgent\] stream payload \[conversation_end\]")
    re_before_invoke_emit = re.compile(r"beforeInvoke sid=\S+ -> emit interrupt_start for request_start")
    re_emit_todo_events = re.compile(r"\[EDPA-DIAG\] emitTodoEvents todos=\d+, fpChanged=\w+ \(.*?new=([^)]+)\)")

    # 自动检测文件编码（支持 UTF-8 / UTF-16 LE/BE，Spring Boot 日志常为 UTF-16）
    file_encoding = "utf-8"
    try:
        with open(log_path, "rb") as _f:
            _head = _f.read(4)
        if _head.startswith(b"\xff\xfe") or _head.startswith(b"\xfe\xff"):
            file_encoding = "utf-16"
    except Exception:
        pass

    with open(log_path, "r", encoding=file_encoding, errors="replace") as f:
        for line_num, line in enumerate(f, 1):
            # 提取时间戳和线程名
            m = TS_PATTERN.match(line)
            if not m:
                continue
            ts_str, thread_name = m.group(1), m.group(2)

            # 只处理包含 conv_id 的行（线程名或 sid 中）
            if conv_id not in thread_name and conv_id not in line:
                continue

            ts = parse_ts(ts_str)

            # ── beforeInvoke / conversation_start → 新一轮开始 ──
            if re_before_invoke_emit.search(line) or re_conversation_start.search(line):
                if current_round is not None:
                    rounds.append(current_round)
                current_round = RoundSummary(
                    round_num=len(rounds) + 1,
                    round_start=ts,
                )
                current_model = None
                current_iter = 0
                current_round.other_events.append((ts, "conversation_start"))
                continue

            if current_round is None:
                continue

            # ── conversation_end → 轮次结束 ──
            if re_conversation_end.search(line):
                current_round.round_end = ts
                current_round.other_events.append((ts, "conversation_end"))
                continue

            # ── ReAct iteration → 标记新一轮 LLM 调用 ──
            m_iter = re_react_iter.search(line)
            if m_iter:
                current_iter = int(m_iter.group(1))
                # 关闭上一个 model call（如果没有 E2E_MODEL_OUTPUT）
                if current_model and current_model.end_time is None:
                    current_model.end_time = ts
                current_model = ModelCall(iter_num=current_iter, start_time=None)
                current_round.other_events.append((ts, f"ReAct iteration {current_iter}"))
                continue

            # ── E2E_MODEL_INPUT → 模型调用开始 ──
            m_input = re_model_input.search(line)
            if m_input:
                msg_count = int(m_input.group(1))
                if current_model is None:
                    current_model = ModelCall(iter_num=current_iter)
                current_model.start_time = ts
                current_round.other_events.append(
                    (ts, f"MODEL_INPUT iter={current_iter} msgCount={msg_count}")
                )
                continue

            # ── emitTodoEvents -> 解析 todo 状态列表 ──
            m_emit = re_emit_todo_events.search(line)
            if m_emit:
                new_part = m_emit.group(1)
                pending_todolist_statuses = []
                for entry in new_part.rstrip(';').split(';'):
                    # entry 格式: UUID:STATUS|[deps] 或 UUID:STATUS
                    parts = entry.split(':')
                    if len(parts) >= 2:
                        status = parts[1].split('|')[0].strip()
                        pending_todolist_statuses.append(status)
                continue

            # ── think_start -> 首个 token 到达 ──
            if re_stream_payload.search(line):
                et = re_stream_payload.search(line).group(1)
                if et == "think_start":
                    if current_model is None:
                        current_model = ModelCall(iter_num=current_iter)
                    if current_model.first_token_time is None:
                        current_model.first_token_time = ts
                elif et == "think_chunk":
                    if current_model:
                        current_model.think_chunks += 1
                elif et == "think_end":
                    pass  # think_end 不单独记录，E2E_MODEL_OUTPUT 更准确
                # 捕获客户端发射事件
                if et in CLIENT_EVENT_IDS:
                    m_content = re.search(r"\[EDPAgent\] stream payload \[\w+\]: (.*)", line)
                    content = m_content.group(1).rstrip() if m_content else ""
                    # todolist_item 补充任务状态
                    if et == "todolist_item" and pending_todolist_statuses:
                        status = pending_todolist_statuses.pop(0)
                        content = f"[{status}] {content}"
                    current_round.client_events.append(ClientEvent(event_id=et, content=content, time=ts))
                continue

            # ── MODEL_RESPONSE → 模型响应解析完成（含工具名）──
            m_resp = re_model_response.search(line)
            if m_resp:
                if current_model:
                    current_model.finish_reason = m_resp.group(1)
                    tool_names_str = m_resp.group(3)
                    current_model.tool_names = [
                        n.strip() for n in tool_names_str.split(",") if n.strip()
                    ]
                continue

            # ── E2E_MODEL_OUTPUT → 模型调用完成 ──
            m_output = re_model_output.search(line)
            if m_output:
                if current_model:
                    current_model.end_time = ts
                    current_model.finish_reason = m_output.group(2)
                    # 将完成的 model call 加入轮次
                    current_round.model_calls.append(current_model)
                    current_model = None
                current_round.other_events.append(
                    (ts, f"MODEL_OUTPUT finishReason={m_output.group(2)}")
                )
                continue

            # ── E2E_MODEL_USAGE → Token 用量 ──
            m_usage = re_model_usage.search(line)
            if m_usage:
                # 关联到最后一个未关联 usage 的 model call
                target = None
                for mc in reversed(current_round.model_calls):
                    if mc.input_tokens == 0:
                        target = mc
                        break
                if target is None and current_model:
                    target = current_model
                if target:
                    target.input_tokens = int(m_usage.group(1))
                    target.output_tokens = int(m_usage.group(2))
                    target.total_tokens = int(m_usage.group(3))
                    target.model_name = m_usage.group(4)
                    target.usage_time = ts
                continue

            # ── LLM tool_call: call_versatile → 缓存参数（完整未截断）──
            # 日志格式：[LLM]   tool_call: call_versatile({"query_intent": "...", ..., "query_description": "..."})
            # 此日志行在 beforeToolCall 之前出现，且未被 abbreviate() 截断，是完整参数
            # query_intent 提取完整信息，query_description 提取前20个字符
            m_llm_tool_call = re.search(r"tool_call:\s*call_versatile\(", line)
            if m_llm_tool_call:
                m_intent = re.search(r'"query_intent"\s*:\s*"([^"]*)"', line)
                m_desc = re.search(r'"query_description"\s*:\s*"([^"]*)"', line)
                fixed_intent = fix_garbled_text(m_intent.group(1)) if m_intent else ""
                # 用模糊匹配从 scriptconfig.yaml 修正乱码的 query_intent
                matched_intent = fuzzy_match_query_intent(fixed_intent)
                pending_versatile_params = {
                    "query_intent": matched_intent if matched_intent else fixed_intent,
                    "query_description": fix_garbled_text(m_desc.group(1))[:20] if m_desc else "",
                    "raw_log": line.rstrip(),  # 保留原始日志行作为证据
                }
                continue

            # ── beforeToolCall → 工具调用开始 ──
            m_bt = re_before_tool.search(line)
            if m_bt:
                tool_name = m_bt.group(1)
                tool_mode = m_bt.group(2)
                tc = ToolCall(tool_name=tool_name, start_time=ts)
                # 提取 tool_start content（同一行的 matched_script，可能含乱码中文）
                m_script = re.search(r"matched_script=(\S+)", line)
                if m_script:
                    fixed_start = fix_garbled_text(m_script.group(1))
                    # 用模糊匹配从 scriptconfig.yaml 修正乱码的 tool_start 文案
                    matched_start = fuzzy_match_tool_start(fixed_start)
                    if matched_start:
                        tc.start_content = matched_start
                    else:
                        tc.start_content = fixed_start
                # 关联缓存的 call_versatile 参数（仅 interrupt-handled 模式才关联）
                if tool_name == "call_versatile" and tool_mode == "interrupt-handled" and pending_versatile_params:
                    tc.query_intent = pending_versatile_params["query_intent"]
                    tc.query_description = pending_versatile_params["query_description"]
                    tc.raw_log = pending_versatile_params.get("raw_log", "")
                    pending_versatile_params = None
                    # 若 query_intent 仍含乱码（不在 scriptconfig 中），从 tool_start 反推
                    mapping = load_scriptconfig()
                    if mapping and tc.query_intent not in mapping and tc.start_content:
                        reversed_intent = tool_start_to_intent(tc.start_content)
                        if reversed_intent:
                            tc.query_intent = reversed_intent
                else:
                    # 非 call_versatile 工具，记录 beforeToolCall 行作为证据
                    tc.raw_log = line.rstrip()
                current_round.tool_calls.append(tc)
                current_round.other_events.append((ts, f"beforeToolCall tool={tool_name}"))
                continue

            # ── tool call completed → 工具调用完成 ──
            m_tc = re_tool_completed.search(line)
            if m_tc:
                tool_name = m_tc.group(1)
                # 找到最后一个未完成的同名工具调用
                for tc in reversed(current_round.tool_calls):
                    if tc.tool_name == tool_name and tc.end_time is None:
                        tc.end_time = ts
                        break
                current_round.other_events.append(
                    (ts, f"tool_completed tool={tool_name}")
                )
                continue

        # ── 文件结束，收尾 ──
    if current_model and current_model.end_time is None:
        current_model.end_time = current_round.round_end if current_round else None
        if current_round:
            current_round.model_calls.append(current_model)
    if current_round:
        if current_round.round_end is None:
            current_round.round_end = current_round.other_events[-1][0] if current_round.other_events else current_round.round_start
        rounds.append(current_round)

    return rounds


def _round_label(r: RoundSummary) -> str:
    """为轮次生成可读名称，基于该轮包含的模型调用和工具调用。

    示例：
      - 无模型无工具 → "conversation_start"
      - 5次LLM + call_versatile → "LLM×5 + call_versatile×2"
      - 3次LLM 无工具 → "LLM×3"
    """
    parts: list[str] = []
    if r.model_calls:
        parts.append(f"LLM×{len(r.model_calls)}")
    if r.tool_calls:
        # 按工具名分组计数
        tool_counts: dict[str, int] = {}
        for t in r.tool_calls:
            tool_counts[t.tool_name] = tool_counts.get(t.tool_name, 0) + 1
        for name, count in tool_counts.items():
            parts.append(f"{name}×{count}" if count > 1 else name)
    if not parts:
        return "conversation_start"
    return " + ".join(parts)


def generate_report(rounds: list[RoundSummary], conv_id: str) -> str:
    """生成 Markdown 格式的耗时分析报告，返回报告内容字符串。"""
    lines: list[str] = []

    def L(s=""):
        lines.append(s)

    L(f"# 会话 {conv_id} 耗时分析报告")
    L()

    total_rounds = len(rounds)
    total_duration = sum(r.total_duration_s for r in rounds)
    total_model_ms = sum(r.model_total_ms for r in rounds)
    total_tool_ms = sum(r.tool_total_ms for r in rounds)
    total_input_tokens = sum(m.input_tokens for r in rounds for m in r.model_calls)
    total_output_tokens = sum(m.output_tokens for r in rounds for m in r.model_calls)
    total_model_s = total_model_ms / 1000 if total_model_ms else 0.0
    total_tool_s = total_tool_ms / 1000 if total_tool_ms else 0.0
    other_s = total_duration - total_model_s - total_tool_s if total_duration > 0 else 0.0
    model_pct = total_model_s / total_duration * 100 if total_duration > 0 else 0.0
    tool_pct = total_tool_s / total_duration * 100 if total_duration > 0 else 0.0
    other_pct = other_s / total_duration * 100 if total_duration > 0 else 0.0

    L("## 总览")
    L()
    L(f"| 指标 | 值 |")
    L(f"|------|------|")
    L(f"| 会话 ID | {conv_id} |")
    L(f"| 轮次数 | {total_rounds} |")
    L(f"| 总耗时 | {total_duration:.1f}s |")
    L(f"| 模型调用总耗时 | {total_model_s:.1f}s ({model_pct:.1f}%) |")
    L(f"| 工具调用总耗时 | {total_tool_s:.1f}s ({tool_pct:.1f}%) |")
    L(f"| 其他耗时 | {other_s:.1f}s ({other_pct:.1f}%) |")
    L(f"| 输入 Token 总量 | {total_input_tokens} |")
    L(f"| 输出 Token 总量 | {total_output_tokens} |")
    L()

    # ── 轮次概览表 ──
    L("## 轮次概览")
    L()
    L(f"| 轮次 | 轮次名称 | 轮次开始 | 耗时(s) | 模型次数 | 工具次数 | 模型耗时(s) | 工具耗时(s) | 输入Tok | 输出Tok |")
    L(f"|------|----------|----------|---------|----------|----------|-------------|-------------|---------|---------|")
    for r in rounds:
        r_start = r.round_start.strftime("%H:%M:%S") if r.round_start else "—"
        r_label = _round_label(r)
        in_tok = sum(m.input_tokens for m in r.model_calls)
        out_tok = sum(m.output_tokens for m in r.model_calls)
        L(f"| {r.round_num} | {r_label} | {r_start} | {r.total_duration_s:.1f} | "
          f"{len(r.model_calls)} | {len(r.tool_calls)} | "
          f"{r.model_total_ms/1000:.1f} | {r.tool_total_ms/1000:.1f} | "
          f"{in_tok} | {out_tok} |")
    L(f"| **合计** | — | — | **{total_duration:.1f}** | "
      f"**{sum(len(r.model_calls) for r in rounds)}** | **{sum(len(r.tool_calls) for r in rounds)}** | "
      f"**{total_model_s:.1f}** | **{total_tool_s:.1f}** | "
      f"**{total_input_tokens}** | **{total_output_tokens}** |")
    L()

    # ── 每轮详细 ──
    L("## 各轮详细")
    L()
    for r in rounds:
        r_label = _round_label(r)
        L(f"### 轮次 {r.round_num}：{r_label}")
        L()
        if r.round_start:
            L(f"- **开始**: {r.round_start.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")
        if r.round_end:
            L(f"- **结束**: {r.round_end.strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]}")
        L(f"- **总耗时**: {r.total_duration_s:.1f}s")
        L()

        # (1) 模型调用明细
        L("#### (1) 模型调用（LLM）")
        L()
        if r.model_calls:
            L(f"| iter | 开始时间 | 首Token时间 | 完成时间 | 总耗时(ms) | TTFT(ms) | 解码(ms) | 输入Tok | 输出Tok | finishReason | toolCalls |")
            L(f"|------|----------|------------|----------|-------------|----------|----------|---------|---------|--------------|-----------|")
            for i, m in enumerate(r.model_calls, 1):
                t_start = m.start_time.strftime("%H:%M:%S.%f")[:-3] if m.start_time else "—"
                t_ft = m.first_token_time.strftime("%H:%M:%S.%f")[:-3] if m.first_token_time else "—"
                t_end = m.end_time.strftime("%H:%M:%S.%f")[:-3] if m.end_time else "—"
                tools = ", ".join(m.tool_names) if m.tool_names else "—"
                L(f"| {i} | {t_start} | {t_ft} | {t_end} | "
                  f"{m.total_duration_ms:.0f} | {m.ttft_ms:.0f} | {m.decode_duration_ms:.0f} | "
                  f"{m.input_tokens} | {m.output_tokens} | {m.finish_reason} | {tools} |")
        else:
            L("无模型调用")
        L()

        # (2) 工具调用明细
        L("#### (2) 工具调用")
        L()
        if r.tool_calls:
            L(f"| 工具名 | 启动时间 | 完成时间 | 耗时(ms) | query_intent | query_description | tool_start文案 |")
            L(f"|--------|----------|----------|----------|--------------|------------------|----------------|")
            for t in r.tool_calls:
                t_start = t.start_time.strftime("%H:%M:%S.%f")[:-3] if t.start_time else "—"
                t_end = t.end_time.strftime("%H:%M:%S.%f")[:-3] if t.end_time else "—"
                qi = t.query_intent or "—"
                qd = t.query_description or "—"
                L(f"| {t.tool_name} | {t_start} | {t_end} | {t.duration_ms:.0f} | {qi} | {qd} | {t.start_content} |")
            L()
            # 原始日志证据
            L("<details><summary>原始日志证据</summary>")
            L()
            for i, t in enumerate(r.tool_calls, 1):
                t_start = t.start_time.strftime("%H:%M:%S.%f")[:-3] if t.start_time else "-"
                L(f"**[{i}] {t.tool_name}** ({t_start})")
                L()
                L("```")
                L(t.raw_log if t.raw_log else "(无原始日志)")
                L("```")
                L()
            L("</details>")
            L()
        else:
            L("无工具调用")
        L()

        # (3) 其他事件时间轴
        L("#### (3) 其他事件时间轴")
        L()
        if r.other_events:
            L(f"| 时间 | 事件 |")
            L(f"|------|------|")
            for t, desc in r.other_events:
                t_str = t.strftime("%H:%M:%S.%f")[:-3] if t else "—"
                L(f"| {t_str} | {desc} |")
        else:
            L("无其他事件")
        L()

        # (4) 客户端发射事件
        L("#### (4) 客户端发射事件")
        L()
        if r.client_events:
            L(f"| 时间 | event_id | 内容 |")
            L(f"|------|----------|------|")
            for ce in r.client_events:
                t_str = ce.time.strftime("%H:%M:%S.%f")[:-3] if ce.time else "-"
                L(f"| {t_str} | {ce.event_id} | {ce.content} |")
        else:
            L("无客户端事件")
        L()

    # ── 瓶颈分析 ──
    L("## 瓶颈分析")
    L()
    all_models = [(r.round_num, i, m) for r in rounds for i, m in enumerate(r.model_calls, 1)]
    if all_models:
        slowest_model = max(all_models, key=lambda x: x[2].total_duration_ms)
        L(f"- **最慢单次模型调用**: 轮次{slowest_model[0]} iter={slowest_model[1]}, "
          f"耗时={slowest_model[2].total_duration_ms:.0f}ms, "
          f"输入={slowest_model[2].input_tokens}tok 输出={slowest_model[2].output_tokens}tok")

    all_tools = [(r.round_num, t) for r in rounds for t in r.tool_calls]
    if all_tools:
        slowest_tool = max(all_tools, key=lambda x: x[1].duration_ms)
        L(f"- **最慢单次工具调用**: 轮次{slowest_tool[0]} tool={slowest_tool[1].tool_name}, "
          f"耗时={slowest_tool[1].duration_ms:.0f}ms")

    L()
    L("### 各轮模型耗时占比")
    L()
    L(f"| 轮次 | 模型占比 | 工具占比 | 其他占比 |")
    L(f"|------|----------|----------|----------|")
    for r in rounds:
        if r.total_duration_s > 0:
            model_pct = r.model_total_ms / (r.total_duration_s * 1000) * 100
            tool_pct = r.tool_total_ms / (r.total_duration_s * 1000) * 100
            other_pct = 100 - model_pct - tool_pct
            L(f"| 轮次{r.round_num} | {model_pct:.1f}% | {tool_pct:.1f}% | {other_pct:.1f}% |")

    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    conv_id = sys.argv[1]
    log_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_LOG_PATH

    print(f"会话 ID: {conv_id}")
    print(f"日志文件: {log_path}")

    rounds = parse_log(log_path, conv_id)
    if not rounds:
        print(f"未在日志中找到会话 {conv_id} 的记录")
        sys.exit(1)

    # 生成 Markdown 报告文件
    report_content = generate_report(rounds, conv_id)
    output_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"con_time_{conv_id}.md")
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(report_content)

    print(f"报告已生成: {output_file}")


if __name__ == "__main__":
    main()
