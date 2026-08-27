#!/usr/bin/env bash
# TC-002-001 · 部署级 E2E（FEAT-002 异构框架托管）：
# 本 runtime 托管一个**真实 AgentScope Agent**，经真 socket 走标准 A2A 往返。
# exit 0=过 / 1=不过 / 3=本维未查成（框架未装）。
#
# ## 它补的是哪一块
#
# 两份 Feat-Func-002b 详设（异构框架适配、本地框架适配）此前登记为
# 「无部署级验证载体」——证据只到进程内判据。
# `agent_runtime/tests/test_agentscope_adapter.py` 后来用真实框架对象把判据层
# 从替身升级成真基类，但它仍跑在 ASGI 传输上、不经网络栈。
#
# 本项目有三例实证：wire 契约缺陷只在真 socket 下暴露（终答被完成信号吞掉、
# 首帧即中断时错误码不对、端侧工具投影丢空串），三例单测全绿。
# 本条把 AgentScope 这条链路补到同一层：真 uvicorn、真端口、真 a2a-sdk。
#
# ## 为什么强制本机后端
#
# `agentscope` 是**可选的框架适配依赖**，不在运行时依赖清单里，也就不在 E2E 镜像里。
# 把它塞进镜像会让其余 16 条脚本都背上一整棵推理栈（anthropic、openai、dashscope、
# mcp、numpy、tree_sitter……），而它们一个都用不到。
#
# 代价如实登记：本条**不覆盖**「干净依赖环境」与「Dockerfile 启动命令」两维
# （见 `_backend.sh` 顶部的覆盖面表），那两维仍由其余脚本的容器后端承担。
#
# ## 期望值来源
#
# 业务正文来自被托管 Agent 的确定性回答（`e2e_agentscope_server.py` 里那一句），
# 状态与帧形态取自本次实跑返回的报文，脚本只做结构断言与关联断言。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-agentscope] $*"; }

# **框架没装就声明未查成，不装作通过**：门禁按退出码分三档，3 = 未判。
# 上一版的部署级门禁靠日志特征词猜未判，被攻破两次——脚本自己知道答案，不该让别人猜。
PY_BIN="$(_e2e_python)"
if ! "$PY_BIN" -c "import agentscope" >/dev/null 2>&1; then
    log "⏭ 未装 AgentScope（可选适配依赖），本维未查成"
    log "   要让本机能判全：pip install agentscope"
    exit 3
fi

# 容器镜像里没有这个可选依赖，走容器后端必然起不来——直接钉死本机后端，
# 免得读数变成一次内容为「模块找不到」的失败。
export E2E_BACKEND=local

e2e_start "e2e_agentscope_server" 18104
e2e_wait_health 60

RPC() {
  curl -s -m 30 "$BASE/a2a/" -H 'Content-Type: application/json' \
    -H 'A2A-Version: 1.0' -d "$1"
}

log "0/7 健康端点报出被托管的框架与版本"
HEALTH=$(curl -s -m 10 "$BASE/health")
log "  $HEALTH"

log "1/7 Card 端点：托管异构框架不改变对外发现面"
CARD=$(curl -s -m 10 "$BASE/a2a/.well-known/agent-card.json")

log "2/7 SendMessage：真实 AgentScope Agent 的回答经 A2A 面回来"
SEND='{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":
  {"messageId":"m-as-1","contextId":"c-as-1","role":"ROLE_USER",
   "parts":[{"text":"查账单"}]}}}'
ANSWER=$(RPC "$SEND")

log "3/7 框架抛异常 → 失败终态，不是链路错误（FEAT-002:116）"
FAILSEND='{"jsonrpc":"2.0","id":"2","method":"SendMessage","params":{"message":
  {"messageId":"m-as-2","contextId":"c-as-2","role":"ROLE_USER",
   "parts":[{"text":"请报错"}]}}}'
FAILED=$(RPC "$FAILSEND")

log "4/7 流式：同一个框架 Agent 经 SSE 面产出帧"
STREAM='{"jsonrpc":"2.0","id":"3","method":"SendStreamingMessage","params":{"message":
  {"messageId":"m-as-3","contextId":"c-as-3","role":"ROLE_USER",
   "parts":[{"text":"查账单"}]}}}'
SSE=$(curl -s -m 30 -N "$BASE/a2a/" -H 'Content-Type: application/json' \
  -H 'A2A-Version: 1.0' -d "$STREAM")

log "5/7 多块产出：每段都在、次序不乱、终答带全文（本件在此翻过三次车）"
MULTISEND='{"jsonrpc":"2.0","id":"4","method":"SendMessage","params":{"message":
  {"messageId":"m-as-4","contextId":"c-as-4","role":"ROLE_USER",
   "parts":[{"text":"查多段账单"}]}}}'
MULTI=$(RPC "$MULTISEND")

log "6/7 零文本产出：框架只回思考块，不得以空完成收尾"
EMPTYSEND='{"jsonrpc":"2.0","id":"5","method":"SendMessage","params":{"message":
  {"messageId":"m-as-5","contextId":"c-as-5","role":"ROLE_USER",
   "parts":[{"text":"我只想想"}]}}}'
EMPTY=$(RPC "$EMPTYSEND")

log "7/7 自定义 REST 面：同一处理器的另一条对外面，终答帧处置规则与 A2A 相反"
REST=$(curl -s -m 30 -N \
  "$BASE/custom/v1/proj/agents/billing/conversations/c-as-6-$$" \
  -H 'Content-Type: application/json' \
  -d '{"input":{"query":"查多段账单"},"stream":true}' \
  -w '\n__HTTP__%{http_code}')

HEALTH="$HEALTH" CARD="$CARD" ANSWER="$ANSWER" FAILED="$FAILED" SSE="$SSE" \
MULTI="$MULTI" EMPTY="$EMPTY" REST="$REST" python3 -c "
import json, os

LEAKS = ('TextBlock', 'agentscope', 'ThinkingBlock')

def no_leak(blob, where):
    # **每一面都查**：上一版只在同步答案与 SSE 两面查，Card 与失败终态两面漏了。
    # 独立复核在那两面各植入一次框架符号，脚本仍 exit 0。
    for leaked in LEAKS:
        assert leaked not in blob, f'{where}：框架符号漏进了对外报文（{leaked}）'

health = json.loads(os.environ['HEALTH'])
assert health.get('framework') == 'agentscope', f'托管的不是本框架：{health}'
version = health.get('framework_version', '')
assert version and version != 'unknown', f'框架版本取不到：{health}'
# 健康端点**有意**报出框架名，是运维观测面，不查泄漏——对外报文那三面才查。

card = json.loads(os.environ['CARD'])
card_blob = json.dumps(card, ensure_ascii=False)
no_leak(card_blob, 'Card 端点')
# **判发现面的结构位，不是只判名字非空**：上一版只有 assert card['name']，
# 把名字改成 'agentscope.TextBlock-leaked-card' 仍然全绿——那条断言判不了
# 它声称判的「不因托管异构框架而变」。
assert card.get('name'), f'Card 没有名字：{card_blob[:300]}'
# 字段名取自本次实跑的卡片，**不凭记忆写**：先写的 protocolVersion 并不存在，
# 这一版协议库发的是 supportedInterfaces 与 version。
for field in ('version', 'capabilities', 'supportedInterfaces',
              'defaultInputModes', 'defaultOutputModes'):
    assert field in card, f'Card 缺发现面字段 {field}：{sorted(card)}'
assert card.get('skills') is not None, f'Card 没有技能位：{sorted(card)}'

answer = json.loads(os.environ['ANSWER'])
assert 'error' not in answer, f'SendMessage 返回了协议错误：{answer}'
# **解析出状态再比对，不在整份报文里找子串**：上一版 assert 正文 in json.dumps(...)，
# 终答在 artifact 还是在完成终态的 message 里，那条断言分不出来——
# 而「完成终态不带终答」正是本仓被部署级 E2E 抓出过的线级缺陷。
task = answer.get('result', {}).get('task', {})
assert task.get('status', {}).get('state') == 'TASK_STATE_COMPLETED', (
    f'同步调用没走到完成终态：{json.dumps(task, ensure_ascii=False)[:400]}')
def artifact_texts(t):
    # **逐份取回，不拼成一串**。上一版 ''.join(...) 之后判子串，对
    # 「发一遍」「发两遍」「发零遍」给出同一个结果——而本件在部署级之外
    # 恰好把这三种形态各翻过一次车，三次这条断言都是绿的。
    return [x for art in t.get('artifacts', []) for part in art.get('parts', [])
            if (x := (part.get('data') or {}).get('content', ''))]

sync_texts = artifact_texts(task)
body = '账单共 12 笔（查账单）'
assert sync_texts == [body, body], (
    f'单块产出的 artifact 形态变了：期望「增量一份 + 全文终答一份」，实得 {sync_texts}')
final_text = json.dumps(task.get('status', {}), ensure_ascii=False)
assert '账单共 12 笔' in final_text, (
    f'完成终态不带终答——A2A 客户端只收到 state=COMPLETED 而丢失最终答案：{final_text[:300]}')
no_leak(json.dumps(answer, ensure_ascii=False), '同步答案')

failed = json.loads(os.environ['FAILED'])
fblob = json.dumps(failed, ensure_ascii=False)
no_leak(fblob, '失败终态')
# 权威 FEAT-002:116：框架以异常表达失败，adapter 必须映射为 FAILED 或等价失败终态
# 并保留异常因果链。**异常穿透出去会变成链路错误**，那是「这个服务坏了」，
# 而权威要的是「你这次调用失败了」——对调用方是两件事。
assert 'error' not in failed, f'框架异常穿透成了协议错误：{fblob[:600]}'
# **解析出状态，不在全文找 failed 字样**：错误正文里就带着那个词。
fstate = failed.get('result', {}).get('task', {}).get('status', {}).get('state', '')
assert fstate == 'TASK_STATE_FAILED', f'框架异常没落到失败终态：{fstate}｜{fblob[:400]}'
assert '模型服务不可达' in fblob, f'异常因果链被抹掉了：{fblob[:600]}'

sse = os.environ['SSE']
# **正常流不带 event: 行**——这是实测确认过的形态，不是推断：
# 标准 A2A 面由 a2a-sdk 出流，它只对含 error 的项附 \`event: error\`。
# （\`run-a2a-northbound.sh\` 的 4 项逐字记着这一条。自定义 REST 面那边的
# \`event: jsonrpc\` 是**另一个面**的帧名，两者不可互抄。）
assert 'data:' in sse, f'SSE 没有 data 行：{sse[:400]}'
assert not [l for l in sse.splitlines() if l.startswith('event:')], (
    f'正常流出现了 event: 行：{sse[:400]}')

# **逐帧解析而不是在原文里找中文**：SSE 那一侧的序列化把非 ASCII 转义
# （\\u8d26\\u5355…），同步那一侧不转义——**两条路径的中文编码本就不同**，
# 这是实跑记载过的对外事实。在原文里找中文，会把编码形态当成缺陷。
frames = [json.loads(l[6:]) for l in sse.splitlines() if l.startswith('data: ')]
assert frames, f'一帧都没解析出来：{sse[:400]}'

states, texts = [], []
for fr in frames:
    result = fr.get('result', {})
    for key in ('task', 'statusUpdate'):
        st = result.get(key, {}).get('status', {}).get('state')
        if st:
            states.append(st)
    art = result.get('artifactUpdate', {}).get('artifact', {})
    for part in art.get('parts', []):
        content = (part.get('data') or {}).get('content')
        if content:
            texts.append(content)

assert any('账单共 12 笔' in x for x in texts), f'流式路径没带出业务正文：{texts}'
# **状态机要真的走完**：只发 SUBMITTED 不发终态时，调用方会一直等，
# 而每一帧单看都是合法的。
assert 'TASK_STATE_COMPLETED' in states, f'流式没走到完成终态：{states}'
no_leak(sse, 'SSE 帧')

# ── 多块产出 ───────────────────────────────────────────────────────
# 部署级此前只产单块，而本件在「多块怎么投影」上翻过三次车
# （丢内容 → 发两遍 → 发零遍），三次都在单块面上绿着。
MULTI_BLOCKS = ['第一段：账单共 12 笔。', '第二段：最近一笔 6312.58 元。']
multi = json.loads(os.environ['MULTI'])
assert 'error' not in multi, f'多块请求返回了协议错误：{multi}'
mtask = multi.get('result', {}).get('task', {})
assert mtask.get('status', {}).get('state') == 'TASK_STATE_COMPLETED', (
    f'多块没走到完成终态：{json.dumps(mtask, ensure_ascii=False)[:400]}')
mtexts = artifact_texts(mtask)
assert mtexts == MULTI_BLOCKS + [''.join(MULTI_BLOCKS)], (
    f'多块 artifact 形态变了：期望「每段各一份 + 全文终答一份」，实得 {mtexts}')
no_leak(json.dumps(multi, ensure_ascii=False), '多块答案')

# ── 零文本产出 ─────────────────────────────────────────────────────
# 框架只回思考块、一个业务文本都没有时，**不得以完成终态收尾**
# （权威明禁把空产出包装成 completed）。这条路径部署级此前零覆盖。
empty = json.loads(os.environ['EMPTY'])
etask = empty.get('result', {}).get('task', {})
estate = etask.get('status', {}).get('state', '')
eblob = json.dumps(empty, ensure_ascii=False)
assert estate != 'TASK_STATE_COMPLETED', (
    f'零文本产出被包装成了完成终态——调用方看到的是「Agent 什么都没说」'
    f'而没有任何线索指向丢帧：{eblob[:400]}')
assert estate, f'零文本产出连终态都没有：{eblob[:400]}'
no_leak(eblob, '零文本答案')

# ── 自定义 REST 面 ─────────────────────────────────────────────────
# **两条对外面的终答帧规则相反**：A2A 对终答帧也出 artifact，
# 自定义 REST 抑制终答帧不出（逐字节对齐存量 format_event 的
# completed → return None）。规则相反就不存在「验一条推另一条」，
# 而本件在 REST 面上真出过线级缺陷（事件名空串让整帧在存量出口被丢弃），
# 出缺陷时 A2A 面全绿。
rest_raw = os.environ['REST']
rest_body, _, rest_code = rest_raw.rpartition('__HTTP__')
assert rest_code.strip() == '200', f'REST 面没返回 200：{rest_code}｜{rest_body[:300]}'
rest_frames = [json.loads(l[6:]) for l in rest_body.splitlines()
               if l.startswith('data: ')]
assert rest_frames, f'REST 面一帧都没解析出来：{rest_body[:400]}'
# **每一帧都要有非空事件名**：空串在存量出口上会让整帧被丢弃，
# 而本 runtime 这一侧看起来完全正常——这正是那次线级缺陷的形态。
# **判取值等值，不判非空**：独立复核第六轮 G4 实测，判非空时把映射换成
# 一个随手编的名字，单元判据零条转红、本脚本 7/7 照样通过。
# 取值抄上游（AgentScopeEventMapper 把文本增量映射为 answer_delta）。
# **末帧是终答块**，事件名取存量的 final_answer_chunk（Feat-Func-002b §4.4；
# 用户 2026-08-25 批准的对外变化第 2 条：本地框架形态的终答在 REST 出口出帧，
# 存量本就发它）。上一版要求全部帧都是 answer_delta、终答帧抑制不出——
# 那正是社区 issue #151 的病灶被写成了判据。
rest_events = [(fr.get('custom_rsp_data', fr) or {}).get('event') for fr in rest_frames]
assert rest_events[-1] == 'final_answer_chunk', f'末帧不是终答块：{rest_events}｜{rest_frames[-1]}'
for got, fr in zip(rest_events[:-1], rest_frames[:-1]):
    assert got == 'answer_delta', f'增量帧的事件名不是上游取值 answer_delta：{got}｜{fr}'
rest_texts = [x for fr in rest_frames
              if (x := (fr.get('custom_rsp_data', fr) or {}).get('content'))]
assert rest_texts == MULTI_BLOCKS + [''.join(MULTI_BLOCKS)], (
    f'REST 面的帧形态变了：期望「每段各一份 + 末尾一帧全文终答」（与存量 summary+final_answer_chunk 并存同形），实得 {rest_texts}')
no_leak(rest_body, 'REST 面')

" || { log "❌ 断言失败"; e2e_diag; exit 1; }

log "通过"
exit 0
