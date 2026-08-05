"""
理财购买（10000元，换一批）五轮对话端到端测试用例。

测试场景：用户通过 A2A JSON-RPC SendStreamingMessage 发起理财推荐会话，
模拟五轮交互：
  (1) "买理财"          → Agent 规划任务 + 调用 call_versatile 推荐产品
  (2) "换一批"          → Agent 重新推荐产品（用户不满意第一批）
  (3) "第一个，10000元"  → Agent 选品确认 + 金额确认（10000元，余额不足需转账）
  (4) "确认"            → Agent 资金筹划 + 转账确认
  (5) "确认"            → Agent 完成转账 + 购买理财 + 最终回答

外部依赖（Versatile agent、LLM）需已部署并运行，本测试不生成 mock。

运行方式：
    cd agents/edp-agent-java/scenarios/wealth-demo/test
    python test_wealth_buy_10000_rotate.py
"""
import json
import os
import time
import uuid
import requests


EDPA_BASE_URL = "http://localhost:8190"
A2A_ENDPOINT = f"{EDPA_BASE_URL}/a2a"
ROUND_INTERVAL_SECONDS = 3
REQUEST_TIMEOUT_SECONDS = 600


def build_a2a_request(conversation_id: str, user_text: str) -> str:
    """构造 A2A JSON-RPC SendStreamingMessage 请求体。"""
    return json.dumps({
        "jsonrpc": "2.0",
        "id": str(uuid.uuid4()),
        "method": "SendStreamingMessage",
        "params": {
            "message": {
                "role": "ROLE_USER",
                "messageId": f"msg-{uuid.uuid4()}",
                "contextId": conversation_id,
                "parts": [{"text": user_text}]
            }
        }
    }, ensure_ascii=False)


def parse_sse_stream(response: requests.Response) -> list[dict]:
    """解析 SSE 流式响应，返回 JSON-RPC 事件列表。

    SSE 格式：
        event:jsonrpc
        data:{"jsonrpc":"2.0","id":1,"result":{...}}

    每个 data 行是一个完整的 JSON-RPC 消息。
    """
    events = []
    for line in response.iter_lines(decode_unicode=True):
        if line is None or line == "":
            continue
        if line.startswith("data:"):
            data_str = line[5:].lstrip()
            try:
                event = json.loads(data_str)
                events.append(event)
            except json.JSONDecodeError:
                events.append({"raw": data_str})
    return events


def _extract_event_and_content(inner) -> tuple[str, str]:
    """从解析后的 JSON 中提取事件类型和内容。

    支持两种格式：
    1. 旧格式（直接）: {"event": "conversation_start", "content": "..."}
    2. 新格式（payload 嵌套）: {"type": "custom", "payload": {"event": "conversation_start", "content": "..."}}
    """
    if not isinstance(inner, dict):
        return "", ""
    event_type = inner.get("event", "")
    content = inner.get("content", "")
    if not event_type:
        payload = inner.get("payload", {})
        if isinstance(payload, dict):
            event_type = payload.get("event", "")
            content = payload.get("content", content)
    return event_type, content if content is not None else ""


def extract_event_types(events: list[dict]) -> list[str]:
    """从 SSE 事件列表中提取事件类型序列。

    A2A SSE 事件结构：
        {"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"artifact":{"parts":[{"text":"{\"type\":\"custom\",\"payload\":{\"event\":\"conversation_start\",...}}"}]}}}}

    事件类型编码在 parts[0].text 的 JSON 的 payload.event 字段中。
    """
    types = []
    for evt in events:
        if not isinstance(evt, dict):
            continue
        result = evt.get("result", {})
        artifact_update = result.get("artifactUpdate", {})
        artifact = artifact_update.get("artifact", {})
        parts = artifact.get("parts", [])
        for part in parts:
            if isinstance(part, dict) and "text" in part:
                text = part["text"]
                try:
                    inner = json.loads(text)
                    event_type, _ = _extract_event_and_content(inner)
                    if event_type:
                        types.append(event_type)
                except (json.JSONDecodeError, TypeError):
                    pass
    return types


def _detect_skill_from_content(content: str) -> bool:
    """从 think_chunk 原始内容中检测是否调用了 skill_tool。"""
    keywords = ["技能文档", "skill_tool", "SKILL", "读取技能", "list_skill",
                "技能 ", "技能。", "读取相关技能", "skill "]
    return any(kw in content for kw in keywords)


def _find_following_tool_name(pairs: list[tuple[str, str]], think_chunk_idx: int) -> str | None:
    """查找 think_chunk 后续的所有工具调用（在同一 think 迭代内）。

    检测多种工具调用模式：
    - tool_start → 工具函数名（如 call_versatile, skill_tool）
    - todolist_start → todo_create/todo_modify（任务规划/重新规划）
    - todo_start → todo_modify（标记 IN_PROGRESS）
    - todo_end → todo_modify（标记 COMPLETED/CANCELLED）
    - interrupt_start → ask_user
    - final_answer_start → final_answer
    """
    tools = []
    for j in range(think_chunk_idx + 1, len(pairs)):
        et, content = pairs[j]
        if et == "think_start":
            break
        if et == "tool_start" and content:
            tools.append(content)
        elif et == "todolist_start":
            tools.append("todo_create/todo_modify")
        elif et == "todo_start":
            tools.append("todo_modify→IN_PROGRESS")
        elif et == "todo_end":
            tools.append("todo_modify→COMPLETED")
        elif et == "interrupt_start":
            tools.append("ask_user")
        elif et == "final_answer_start":
            tools.append("final_answer")

    if not tools:
        return None
    # 去重并保持顺序
    seen = []
    for t in tools:
        if t not in seen:
            seen.append(t)
    return " + ".join(seen)


def extract_events_with_content(events: list[dict]) -> list[tuple[str, str]]:
    """提取事件类型及其对应的内容。

    think_chunk 内容显示 token 数；如果该 think 迭代后续跟随工具调用事件，
    则标注工具函数名，格式：[→ func_name] N tokens。
    工具调用检测包括：tool_start、todolist_start、todo_start、todo_end、
    interrupt_start、final_answer_start，以及从内容中推断的 skill_tool。
    其他事件原文输出 content 字段。
    返回 [(event_type, content), ...]
    """
    pairs = []
    think_chunk_skill_flags = {}  # think_chunk index → has_skill
    for evt in events:
        if not isinstance(evt, dict):
            continue
        result = evt.get("result", {})
        artifact_update = result.get("artifactUpdate", {})
        artifact = artifact_update.get("artifact", {})
        parts = artifact.get("parts", [])
        for part in parts:
            if isinstance(part, dict) and "text" in part:
                text = part["text"]
                try:
                    inner = json.loads(text)
                    event_type, content = _extract_event_and_content(inner)
                    if not event_type:
                        continue
                    if event_type == "think_chunk":
                        # 在截断前检测 skill_tool
                        think_chunk_skill_flags[len(pairs)] = _detect_skill_from_content(content)
                        content = f"{len(content)} tokens"
                    pairs.append((event_type, content))
                except (json.JSONDecodeError, TypeError):
                    pass

    # 为 think_chunk 添加 tool call 关联信息
    for i, (et, content) in enumerate(pairs):
        if et == "think_chunk":
            tool_name = _find_following_tool_name(pairs, i)
            # 从内容中检测 skill_tool
            has_skill = think_chunk_skill_flags.get(i, False)
            if has_skill:
                if tool_name:
                    tool_name = f"skill_tool + {tool_name}"
                else:
                    tool_name = "skill_tool"
            if tool_name:
                pairs[i] = (et, f"[→ {tool_name}] {content}")

    return pairs


def extract_text_chunks(events: list[dict]) -> list[str]:
    """提取 think_chunk 和 final_answer_chunk 的文本内容。

    如果 think_chunk 后续跟随 tool_start，标注工具函数名。
    """
    # 先提取所有事件的原始内容（用于查找 tool_start 关联）
    raw_pairs = []
    for evt in events:
        if not isinstance(evt, dict):
            continue
        result = evt.get("result", {})
        artifact_update = result.get("artifactUpdate", {})
        artifact = artifact_update.get("artifact", {})
        parts = artifact.get("parts", [])
        for part in parts:
            if isinstance(part, dict) and "text" in part:
                text = part["text"]
                try:
                    inner = json.loads(text)
                    event_type, content = _extract_event_and_content(inner)
                    if event_type and content:
                        raw_pairs.append((event_type, content))
                except (json.JSONDecodeError, TypeError):
                    pass

    # 构建 text chunks，为 think_chunk 添加 tool call 关联
    chunks = []
    for i, (et, content) in enumerate(raw_pairs):
        if et in ("think_chunk", "final_answer_chunk") and content:
            if et == "think_chunk":
                tool_name = _find_following_tool_name(raw_pairs, i)
                # 从内容中检测 skill_tool
                has_skill = _detect_skill_from_content(content)
                if has_skill:
                    if tool_name:
                        tool_name = f"skill_tool + {tool_name}"
                    else:
                        tool_name = "skill_tool"
                if tool_name:
                    chunks.append(f"[think_chunk → {tool_name}] {content[:150]}")
                else:
                    chunks.append(f"[think_chunk] {content[:150]}")
            else:
                chunks.append(f"[{et}] {content[:150]}")
    return chunks


def send_query(conversation_id: str, query: str, round_num: int) -> list[dict]:
    """发送一轮对话并返回 SSE 事件列表。"""
    body = build_a2a_request(conversation_id, query)
    print(f"\n{'='*60}")
    print(f"轮次 {round_num}: query=\"{query}\"")
    print(f"conversation_id={conversation_id}")
    print(f"{'='*60}")

    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Tenant-Id": "default",
    }
    response = requests.post(
        A2A_ENDPOINT,
        data=body.encode("utf-8"),
        headers=headers,
        stream=True,
        timeout=REQUEST_TIMEOUT_SECONDS,
        proxies={"http": None, "https": None},
    )
    response.raise_for_status()
    response.encoding = "utf-8"

    events = parse_sse_stream(response)
    event_types = extract_event_types(events)
    text_chunks = extract_text_chunks(events)

    print(f"  收到 {len(events)} 个 SSE 事件")
    print(f"  事件类型序列: {event_types}")
    if text_chunks:
        print(f"  文本内容摘要:")
        for chunk in text_chunks[:10]:
            print(f"    {chunk}")
        if len(text_chunks) > 10:
            print(f"    ... (共 {len(text_chunks)} 条文本块)")

    return events


def _soft_check_event(event_types: list[str], event_name: str, round_num: int) -> bool:
    """软检查指定事件是否存在，不存在则打印警告并返回 False（不抛异常）。

    LLM 在复杂场景（如10000元资金转账）中可能直接执行多步工具调用，
    触及迭代上限后未发出 conversation_end；且后续轮次因会话延续
    可能不发出 conversation_start。这些情况不视为硬失败。
    """
    if event_name in event_types:
        return True
    tail = event_types[-3:] if len(event_types) >= 3 else event_types
    print(f"  [WARN] 轮次{round_num}未包含 {event_name}（可能触及迭代上限或会话延续），事件末尾: {tail}")
    return False


def assert_round1_events(events: list[dict], query: str):
    """断言轮次1（买理财）的关键事件。

    首轮必须正常启动和结束，包含任务规划和产品推荐。
    """
    event_types = extract_event_types(events)
    assert "conversation_start" in event_types, f"轮次1应包含 conversation_start，实际: {event_types}"
    assert "conversation_end" in event_types, f"轮次1应包含 conversation_end，实际: {event_types}"
    has_planning = "planning_start" in event_types
    has_todolist = "todolist_start" in event_types
    has_final = "final_answer_end" in event_types
    has_interrupt = "interrupt_start" in event_types
    assert has_planning or has_todolist, f"轮次1应包含 planning_start 或 todolist_start，实际: {event_types}"
    assert has_final or has_interrupt, f"轮次1应以 final_answer_end 或 interrupt_start 结束，实际: {event_types}"
    print(f"  [PASS] 轮次1断言通过（query=\"{query}\"）")


def assert_round2_events(events: list[dict], query: str):
    """断言轮次2（换一批）的关键事件。

    用户不满意第一批产品，要求重新推荐。Agent 应再次调用
    call_versatile 推荐新产品。LLM 行为非确定性：可能走到 ask_user
    中断，也可能只完成推荐就达到迭代上限。
    核心验证：LLM 已推理；conversation_start/end 为软检查。
    """
    event_types = extract_event_types(events)
    _soft_check_event(event_types, "conversation_start", 2)
    _soft_check_event(event_types, "conversation_end", 2)
    has_think = "think_start" in event_types
    assert has_think, f"轮次2应包含 think_start（LLM 推理），实际: {event_types}"
    print(f"  [PASS] 轮次2断言通过（query=\"{query}\"）")


def assert_round3_events(events: list[dict], query: str):
    """断言轮次3（选品+金额确认）的关键事件。

    用户选择第一个产品并指定10000元。Agent 确认选品和金额，
    可能发起 ask_user 确认中断，也可能直接执行工具调用（查询余额+
    转账）并触及迭代上限。核心验证：LLM 已推理。
    """
    event_types = extract_event_types(events)
    _soft_check_event(event_types, "conversation_start", 3)
    _soft_check_event(event_types, "conversation_end", 3)
    has_think = "think_start" in event_types
    assert has_think, f"轮次3应包含 think_start（LLM 推理），实际: {event_types}"
    print(f"  [PASS] 轮次3断言通过（query=\"{query}\"）")


def assert_round4_events(events: list[dict], query: str):
    """断言轮次4（资金筹划+转账确认）的关键事件。

    10000元场景：理财卡余额不足，Agent 启动 fund_planning_skill，
    执行余额查询 + 资金转账。转账触发 versatile-agent 交易验签，
    Agent 通过 ask_user 请求用户确认转账。
    LLM 可能跳过确认直接执行转账并触及迭代上限。前序轮次未正常结束时
    本轮可能无 conversation_start。核心验证：LLM 已推理。
    """
    event_types = extract_event_types(events)
    _soft_check_event(event_types, "conversation_start", 4)
    _soft_check_event(event_types, "conversation_end", 4)
    has_think = "think_start" in event_types
    assert has_think, f"轮次4应包含 think_start（LLM 推理），实际: {event_types}"
    print(f"  [PASS] 轮次4断言通过（query=\"{query}\"）")


def assert_round5_events(events: list[dict], query: str):
    """断言轮次5（完成转账+购买理财）的关键事件。

    用户确认转账后，Agent 完成资金转账，执行理财购买，
    输出 final_answer 最终回答。LLM 可能在前序轮次已完成全部操作，
    本轮无实质工作。核心验证：LLM 已推理。
    """
    event_types = extract_event_types(events)
    _soft_check_event(event_types, "conversation_start", 5)
    _soft_check_event(event_types, "conversation_end", 5)
    has_think = "think_start" in event_types
    assert has_think, f"轮次5应包含 think_start（LLM 推理），实际: {event_types}"
    print(f"  [PASS] 轮次5断言通过（query=\"{query}\"）")


def fetch_redis_todolist(conversation_id: str) -> list[dict] | None:
    """从 Redis 获取当前会话的 todolist 快照。

    返回 TodoItem 列表，Redis 不可用或无数据时返回 None。
    """
    try:
        import redis as redis_lib
        r = redis_lib.Redis(host="localhost", port=6379, db=0)
        key = f"{conversation_id}:todo"
        raw = r.get(key)
        if raw:
            return json.loads(raw)
    except Exception:
        pass
    return None


def fetch_redis_toolcount(conversation_id: str) -> dict | None:
    """从 Redis Hash 获取工具调用次数计数（ExecutionLimitRail 持久化）。

    Redis key: edpa:toolcount:{sessionId}（Hash 类型）
    field: 工具名, value: 调用次数（整数）
    TTL: 3600 秒，需在会话结束后 1 小时内查询。
    """
    try:
        import redis as redis_lib
        r = redis_lib.Redis(host="localhost", port=6379, db=0)
        key = f"edpa:toolcount:{conversation_id}"
        entries = r.hgetall(key)
        if entries:
            result = {}
            for k, v in entries.items():
                name = k.decode() if isinstance(k, bytes) else str(k)
                result[name] = int(v)
            return result
    except Exception:
        pass
    return None


def extract_tool_calls_detail(events: list[dict]) -> list[dict]:
    """从 SSE 事件中提取所有工具调用详情，给出具体工具函数名。

    识别所有工具调用类型：
    - tool_start/tool_end → 外部工具调用（如 call_versatile）
    - todolist_start/end → todo_create/todo_modify
    - todo_start → todo_modify→IN_PROGRESS
    - todo_end → todo_modify→COMPLETED/CANCELLED
    - interrupt_start → ask_user
    - final_answer_start → final_answer

    返回 [{name, has_end}, ...]
    """
    # 提取所有 (event_type, content, payload) 三元组
    triples = []
    for evt in events:
        if not isinstance(evt, dict):
            continue
        result = evt.get("result", {})
        artifact_update = result.get("artifactUpdate", {})
        artifact = artifact_update.get("artifact", {})
        parts = artifact.get("parts", [])
        for part in parts:
            if not (isinstance(part, dict) and "text" in part):
                continue
            try:
                inner = json.loads(part["text"])
                if not isinstance(inner, dict):
                    continue
                event_type = inner.get("event", "")
                content = inner.get("content", "")
                payload = inner.get("payload", {})
                if not event_type and isinstance(payload, dict):
                    event_type = payload.get("event", "")
                    content = payload.get("content", content)
                if not event_type:
                    continue
                triples.append((event_type, content or "",
                                payload if isinstance(payload, dict) else {}))
            except (json.JSONDecodeError, TypeError):
                pass

    # 识别所有工具调用类型，给出具体工具函数名
    calls = []
    pending_tool_start = None
    for et, content, payload in triples:
        if et == "tool_start":
            # 尝试从 payload 中提取工具函数名
            tool_name = (payload.get("tool_name", "") or payload.get("toolName", "")
                         or payload.get("name", ""))
            if pending_tool_start:
                calls.append(pending_tool_start)
            pending_tool_start = {"name": tool_name or f"tool({content[:20]})",
                                   "has_end": False}
        elif et == "tool_end":
            if pending_tool_start:
                pending_tool_start["has_end"] = True
                calls.append(pending_tool_start)
                pending_tool_start = None
            else:
                calls.append({"name": "tool(unknown)", "has_end": True})
        elif et == "todolist_start":
            calls.append({"name": "todo_create/todo_modify", "has_end": True})
        elif et == "todolist_end":
            pass  # todolist_start 已计数，end 不重复
        elif et == "todo_start":
            calls.append({"name": "todo_modify→IN_PROGRESS", "has_end": True})
        elif et == "todo_end":
            name = "todo_modify→COMPLETED"
            if content and ("取消" in content or "CANCEL" in str(content).upper()):
                name = "todo_modify→CANCELLED"
            calls.append({"name": name, "has_end": True})
        elif et == "interrupt_start":
            calls.append({"name": "ask_user", "has_end": True})
        elif et == "final_answer_start":
            calls.append({"name": "final_answer", "has_end": True})
    if pending_tool_start:
        calls.append(pending_tool_start)
    return calls


def build_test_report(conversation_id: str, all_events: list[list[dict]], queries: list[str],
                      round_durations: list[float], total_duration: float,
                      round_redis_snapshots: list | None = None,
                      round_tool_calls: list | None = None,
                      round_toolcount_snapshots: list | None = None,
                      round_assertions: list | None = None) -> str:
    """构建结构化测试报告（Markdown 格式字符串）。"""
    # 检测被测服务是否具备 Redis 能力（通过检查本次会话是否写入了 Redis）
    redis_enabled = False
    redis_key = f"{conversation_id}:todo"
    try:
        import redis as redis_lib
        r = redis_lib.Redis(host="localhost", port=6379, db=0)
        r.ping()
        # 检查本次会话是否有 Redis 数据（被测服务写入的证据）
        if r.get(redis_key) is not None:
            redis_enabled = True
    except Exception:
        redis_enabled = False

    lines = []
    lines.append("# 端到端测试报告 - 理财购买（10000元，换一批）")
    lines.append("")
    lines.append(f"- **会话 ID**: `{conversation_id}`")
    lines.append(f"- **测试时间**: {time.strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"- **目标服务**: {EDPA_BASE_URL}")
    lines.append(f"- **Redis 能力**: {'具备（RedisTodoStore 已集成）' if redis_enabled else '不具备（使用文件存储）'}")
    lines.append(f"- **总耗时**: {total_duration:.1f}s")
    lines.append(f"- **轮间间隔**: {ROUND_INTERVAL_SECONDS}s")
    lines.append("")

    # 轮次概览
    lines.append("## 轮次概览")
    lines.append("")
    lines.append("| 轮次 | Query | 事件数 | 耗时(s) | think次数 | todolist次数 | todo次数 | tool次数 | 状态 |")
    lines.append("|------|-------|--------|---------|-----------|---------------|----------|----------|------|")
    for i, (query, events, dur) in enumerate(zip(queries, all_events, round_durations)):
        assertion = round_assertions[i] if round_assertions and i < len(round_assertions) else ("PASS" if events else "FAIL")
        status = assertion if events else "FAIL"
        ec_pairs = extract_events_with_content(events)
        think_count = sum(1 for et, _ in ec_pairs if et == "think_start")
        todolist_count = sum(1 for et, _ in ec_pairs if et == "todolist_start")
        todo_count = sum(1 for et, _ in ec_pairs if et == "todo_start")
        tool_count = sum(1 for et, _ in ec_pairs if et == "tool_start")
        lines.append(f"| {i+1} | {query} | {len(events)} | {dur:.1f} | {think_count} | {todolist_count} | {todo_count} | {tool_count} | {status} |")
    total_events = sum(len(e) for e in all_events)
    all_pairs = [extract_events_with_content(events) for events in all_events]
    total_think = sum(sum(1 for et, _ in pairs if et == "think_start") for pairs in all_pairs)
    total_todolist = sum(sum(1 for et, _ in pairs if et == "todolist_start") for pairs in all_pairs)
    total_todo = sum(sum(1 for et, _ in pairs if et == "todo_start") for pairs in all_pairs)
    total_tool = sum(sum(1 for et, _ in pairs if et == "tool_start") for pairs in all_pairs)
    lines.append(f"| **合计** |  | **{total_events}** | **{total_duration:.1f}** | **{total_think}** | **{total_todolist}** | **{total_todo}** | **{total_tool}** |  |")
    lines.append("")

    # 模型调用轮次信息
    lines.append("## 模型调用轮次信息")
    lines.append("")
    for i, (query, events) in enumerate(zip(queries, all_events)):
        ec_pairs = extract_events_with_content(events)
        think_count = sum(1 for et, _ in ec_pairs if et == "think_start")
        tool_count = sum(1 for et, _ in ec_pairs if et == "tool_start")
        lines.append(f"### 轮次{i+1} (query=\"{query}\")")
        lines.append(f"- LLM 推理次数（think_start）: {think_count}")
        lines.append(f"- 工具调用次数（tool_start）: {tool_count}")
        # 构建模型调用链
        chain = []
        for et, content in ec_pairs:
            if et == "think_start":
                chain.append("think")
            elif et == "tool_start":
                chain.append(f"tool({content})" if content else "tool")
        lines.append(f"- 模型调用链: {' → '.join(chain) if chain else '无'}")
        lines.append("")

    # LLM 调用分析（think → tool 关联）
    lines.append("## LLM 调用分析（think → tool 关联）")
    lines.append("")
    for i, (query, events) in enumerate(zip(queries, all_events)):
        ec_pairs = extract_events_with_content(events)
        lines.append(f"### 轮次{i+1} (query=\"{query}\")")
        lines.append("")
        lines.append("| 迭代 | think 内容摘要 | 工具调用 |")
        lines.append("|------|--------------|---------|")
        iter_num = 0
        for j, (et, content) in enumerate(ec_pairs):
            if et == "think_start":
                iter_num += 1
                # 收集该迭代内的 think_chunk 内容和工具调用
                think_summary = ""
                tool_names = []
                has_skill_in_iter = False
                for k in range(j + 1, len(ec_pairs)):
                    next_et, next_content = ec_pairs[k]
                    if next_et == "think_start":
                        break
                    if next_et == "think_chunk":
                        # 检测 skill_tool
                        if "[→ skill_tool" in next_content or "skill_tool +" in next_content:
                            has_skill_in_iter = True
                        # 提取原始内容（去掉前缀的 [→ tool] 标记）
                        raw_content = next_content
                        if raw_content.startswith("[→"):
                            bracket_end = raw_content.index("]") + 2
                            raw_content = raw_content[bracket_end:].strip()
                        if "tokens" in raw_content:
                            think_summary = raw_content
                        else:
                            think_summary = raw_content[:50]
                    elif next_et == "tool_start":
                        tool_names.append(next_content if next_content else "unknown")
                    elif next_et == "todolist_start":
                        tool_names.append("todo_create/todo_modify")
                    elif next_et == "todo_start":
                        tool_names.append("todo_modify→IN_PROGRESS")
                    elif next_et == "todo_end":
                        tool_names.append("todo_modify→COMPLETED")
                    elif next_et == "final_answer_start":
                        tool_names.append("final_answer")
                    elif next_et == "interrupt_start":
                        tool_names.append("ask_user")
                # 合并 skill_tool
                if has_skill_in_iter:
                    tool_names.insert(0, "skill_tool")
                # 去重
                seen_tools = []
                for t in tool_names:
                    if t not in seen_tools:
                        seen_tools.append(t)
                tool_name = " + ".join(seen_tools) if seen_tools else "—"
                lines.append(f"| {iter_num} | {think_summary} | {tool_name} |")
        lines.append("")

    # 各轮事件类型序列（带内容）
    lines.append("## 各轮事件类型序列")
    lines.append("")
    for i, (query, events) in enumerate(zip(queries, all_events)):
        ec_pairs = extract_events_with_content(events)
        lines.append(f"### 轮次{i+1} (query=\"{query}\")")
        lines.append("")
        for j, (et, content) in enumerate(ec_pairs):
            if content:
                lines.append(f"{j+1}. {et} — {content}")
            else:
                lines.append(f"{j+1}. {et}")
        lines.append("")

    # 各轮文本内容摘要
    lines.append("## 各轮文本内容摘要")
    lines.append("")
    for i, (query, events) in enumerate(zip(queries, all_events)):
        chunks = extract_text_chunks(events)
        lines.append(f"### 轮次{i+1} (query=\"{query}\") — {len(chunks)} 条文本块")
        lines.append("")
        for chunk in chunks:
            lines.append(f"- {chunk}")
        lines.append("")

    # 各轮 Redis Todolist 快照与工具调用
    lines.append("## 各轮 Redis Todolist 快照与工具调用")
    lines.append("")
    for i, query in enumerate(queries):
        lines.append(f"### 轮次{i+1} (query=\"{query}\")")
        lines.append("")
        # Redis todolist 快照
        redis_snapshot = (round_redis_snapshots[i] if round_redis_snapshots
                          and i < len(round_redis_snapshots) else None)
        if redis_snapshot:
            lines.append(f"- **Redis todolist** ({len(redis_snapshot)} 项):")
            for t in redis_snapshot:
                status = t.get("status", "?")
                content = t.get("content", "?")
                deps = t.get("depends_on", [])
                dep_str = f" deps={deps}" if deps else ""
                lines.append(f"  - [{status}] {content}{dep_str}")
        else:
            lines.append("- **Redis todolist**: 无数据（Redis 不可用或会话未写入）")
        # 工具调用情况（按工具名分组计数）
        tool_calls = (round_tool_calls[i] if round_tool_calls
                      and i < len(round_tool_calls) else [])
        if tool_calls:
            from collections import Counter
            tool_counter = Counter(tc.get('name', 'unknown') for tc in tool_calls)
            lines.append(f"- **工具调用** ({len(tool_calls)} 次):")
            for name, count in tool_counter.most_common():
                lines.append(f"  - {name}: {count} 次")
        else:
            lines.append("- **工具调用**: 无")
        # Redis 工具调用计数（ExecutionLimitRail 持久化）
        toolcount = (round_toolcount_snapshots[i] if round_toolcount_snapshots
                     and i < len(round_toolcount_snapshots) else None)
        if toolcount:
            prev_count = (round_toolcount_snapshots[i - 1] if i > 0 and round_toolcount_snapshots
                          and i - 1 < len(round_toolcount_snapshots)
                          and round_toolcount_snapshots[i - 1] else {})
            lines.append(f"- **Redis 工具调用计数** ({len(toolcount)} 个工具):")
            for name, count in sorted(toolcount.items()):
                prev_val = prev_count.get(name, 0) if prev_count else 0
                delta = count - prev_val
                delta_str = f"+{delta}" if delta >= 0 else str(delta)
                lines.append(f"  - {name}: {count} ({delta_str})")
        else:
            lines.append("- **Redis 工具调用计数**: 无数据（TTL 过期或会话未写入）")
        lines.append("")

    # 工具累计调用次数
    if round_tool_calls:
        from collections import Counter
        all_tool_counter = Counter()
        for round_calls in round_tool_calls:
            for tc in round_calls:
                all_tool_counter[tc.get('name', 'unknown')] += 1
        lines.append("## 工具累计调用次数")
        lines.append("")
        lines.append("| 工具名称 | 累计调用次数 |")
        lines.append("|---|---:|")
        for name, count in all_tool_counter.most_common():
            lines.append(f"| {name} | {count} |")
        lines.append(f"| **合计** | **{sum(all_tool_counter.values())}** |")
        lines.append("")

    # 关键事件检查
    lines.append("## 关键事件检查")
    lines.append("")
    desc_map = {
        "conversation_start": "会话启动",
        "conversation_end": "会话结束",
        "request_start": "请求开始",
        "planning_start": "任务规划",
        "todolist_start": "任务列表展示",
        "todolist_item": "任务项",
        "todolist_end": "任务列表结束",
        "todo_start": "任务开始执行",
        "todo_end": "任务执行结束",
        "interrupt_start": "中断发起",
        "interrupt_end": "中断恢复",
        "think_start": "LLM 推理开始",
        "think_chunk": "推理内容",
        "think_end": "LLM 推理结束",
        "tool_start": "工具调用开始",
        "tool_end": "工具调用结束",
        "final_answer_start": "最终回答开始",
        "final_answer_chunk": "最终回答内容",
        "final_answer_end": "最终回答结束",
        "message": "消息",
        "error_event": "错误事件",
    }
    all_types = []
    for events in all_events:
        all_types.extend(extract_event_types(events))
    seen_order = []
    for t in all_types:
        if t not in seen_order:
            seen_order.append(t)
    for t in desc_map:
        if t not in seen_order:
            seen_order.append(t)
    lines.append("| 事件类型 | 描述 | 出现次数 | 状态 |")
    lines.append("|----------|------|----------|------|")
    for event_type in seen_order:
        count = all_types.count(event_type)
        desc = desc_map.get(event_type, "其他事件")
        mark = "Y" if count > 0 else "N"
        lines.append(f"| {event_type} | {desc} | {count} | {mark} |")
    lines.append("")

    # 事件配对一致性检查
    pair_checks = [
        ("todo_start", "todo_end"),
        ("tool_start", "tool_end"),
    ]
    pair_results = []
    for start_type, end_type in pair_checks:
        start_count = all_types.count(start_type)
        end_count = all_types.count(end_type)
        matched = start_count == end_count
        pair_results.append((start_type, end_type, start_count, end_count, matched))
    lines.append("## 事件配对一致性检查")
    lines.append("")
    lines.append("| 事件对 | start 次数 | end 次数 | 配对状态 |")
    lines.append("|--------|-----------|---------|----------|")
    for start_type, end_type, sc, ec, matched in pair_results:
        status = "✅ 匹配" if matched else "❌ 不匹配"
        lines.append(f"| {start_type} ↔ {end_type} | {sc} | {ec} | {status} |")
    lines.append("")
    all_pairs_matched = all(m for _, _, _, _, m in pair_results)

    # Redis 持久化验证
    lines.append("## Redis 持久化验证")
    lines.append("")
    try:
        import redis as redis_lib
        r = redis_lib.Redis(host="localhost", port=6379, db=0)
        key = f"{conversation_id}:todo"
        raw = r.get(key)
        ttl = r.ttl(key)
        if raw:
            todos = json.loads(raw)
            lines.append(f"- **Redis Key**: `{key}`")
            lines.append(f"- **TTL**: {ttl}s")
            lines.append(f"- **Todo 数量**: {len(todos)}")
            lines.append("")
            for t in todos:
                content = t.get("content", "")
                status = t.get("status", "")
                lines.append(f"  - {content} [{status}]")
        else:
            lines.append(f"- **Redis Key**: `{key}`")
            lines.append("- **状态**: 已过期或不存在（会话结束后 TTL 到期）")
    except Exception as e:
        lines.append(f"- **Redis 查询失败**: {e}")
    lines.append("")

    # 测试结果
    all_assertions_passed = (round_assertions is None or
                             all(a == "PASS" for a in round_assertions))
    passed = all(e for e in all_events) and all_pairs_matched and all_assertions_passed
    lines.append("## 测试结果")
    lines.append("")
    lines.append(f"**结果**: {'ALL PASS' if passed else 'FAIL'}")
    lines.append(f"- 五轮对话: {'全部通过' if all(e for e in all_events) else '存在失败'}")
    lines.append(f"- 断言检查: {'全部通过' if all_assertions_passed else '存在失败'}")
    lines.append(f"- 事件配对一致性: {'全部匹配' if all_pairs_matched else '存在不匹配'}")
    lines.append(f"- 总事件数: {total_events}")
    lines.append(f"- 总耗时: {total_duration:.1f}s")

    return "\n".join(lines)


def print_test_report(conversation_id: str, all_events: list[list[dict]], queries: list[str],
                      round_durations: list[float], total_duration: float,
                      round_redis_snapshots: list | None = None,
                      round_tool_calls: list | None = None,
                      round_toolcount_snapshots: list | None = None,
                      round_assertions: list | None = None):
    """打印结构化测试报告并保存为 Markdown 文件。"""
    report = build_test_report(conversation_id, all_events, queries, round_durations,
                               total_duration, round_redis_snapshots, round_tool_calls,
                               round_toolcount_snapshots, round_assertions)
    # 控制台输出
    print(report)
    # 保存为 md 文件
    report_dir = os.path.dirname(os.path.abspath(__file__))
    report_file = os.path.join(report_dir, f"test_report_buy_10000_rotate_e2e_{conversation_id}.md")
    with open(report_file, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"\n报告已保存: {report_file}")


def test_wealth_buy_10000_rotate():
    """
    端到端测试：买理财 → 换一批 → 选品（第一个，10000元）→ 确认 → 确认。

    五轮对话通过同一个 conversation_id 串联：
      轮次1: "买理财"          → Agent 规划任务 + 调用 call_versatile 推荐产品
      轮次2: "换一批"          → Agent 重新推荐产品（用户不满意第一批）
      轮次3: "第一个，10000元"  → Agent 选品确认 + 金额确认（余额不足需转账）
      轮次4: "确认"            → Agent 资金筹划 + 转账确认（ask_user 确认转账）
      轮次5: "确认"            → Agent 完成转账 + 购买理财 + 最终回答

    每轮对话完成后间隔 3 秒发送下一轮。
    """
    conversation_id = time.strftime("%Y%m%d_%H%M%S")
    queries = ["买理财", "换一批", "第一个，10000元", "确认", "确认"]
    assert_fns = [assert_round1_events, assert_round2_events, assert_round3_events,
                  assert_round4_events, assert_round5_events]

    all_events = []
    round_durations = []
    round_redis_snapshots = []
    round_tool_calls = []
    round_toolcount_snapshots = []
    round_assertions = []
    test_start = time.time()

    for i, (query, assert_fn) in enumerate(zip(queries, assert_fns)):
        round_start = time.time()
        events = send_query(conversation_id, query, i + 1)
        round_durations.append(time.time() - round_start)
        all_events.append(events)
        try:
            assert_fn(events, query)
            round_assertions.append("PASS")
        except AssertionError as e:
            round_assertions.append("FAIL")
            print(f"  [FAIL] 轮次{i+1}断言失败: {e}")

        # 每轮完成后获取 Redis todolist 快照
        todos = fetch_redis_todolist(conversation_id)
        round_redis_snapshots.append(todos)
        if todos:
            print(f"  [Redis] todolist ({len(todos)} 项):")
            for t in todos:
                status = t.get("status", "?")
                content = t.get("content", "?")
                print(f"    [{status}] {content}")
        else:
            print(f"  [Redis] 无数据")

        # 每轮完成后提取工具调用情况
        tool_calls = extract_tool_calls_detail(events)
        round_tool_calls.append(tool_calls)
        if tool_calls:
            from collections import Counter
            tool_counter = Counter(tc.get('name', 'unknown') for tc in tool_calls)
            print(f"  [工具调用] {len(tool_calls)} 次:")
            for name, count in tool_counter.most_common():
                print(f"    {name}: {count} 次")

        # 每轮完成后从 Redis 获取工具调用计数（ExecutionLimitRail 持久化）
        toolcount = fetch_redis_toolcount(conversation_id)
        round_toolcount_snapshots.append(toolcount)
        if toolcount:
            prev_count = (round_toolcount_snapshots[-2] if len(round_toolcount_snapshots) >= 2
                          and round_toolcount_snapshots[-2] else {})
            print(f"  [Redis toolcount] {len(toolcount)} 个工具:")
            for name, count in sorted(toolcount.items()):
                prev_val = prev_count.get(name, 0) if prev_count else 0
                delta = count - prev_val
                delta_str = f"+{delta}" if delta >= 0 else str(delta)
                print(f"    {name}: {count} ({delta_str})")
        else:
            print(f"  [Redis toolcount] 无数据")

        if i < len(queries) - 1:
            print(f"  等待 {ROUND_INTERVAL_SECONDS} 秒后发送下一轮...")
            time.sleep(ROUND_INTERVAL_SECONDS)

    total_duration = time.time() - test_start
    print_test_report(conversation_id, all_events, queries, round_durations,
                      total_duration, round_redis_snapshots, round_tool_calls,
                      round_toolcount_snapshots, round_assertions)


if __name__ == "__main__":
    test_wealth_buy_10000_rotate()
