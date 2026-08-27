#!/usr/bin/env bash
# TC-022-WFSTREAM · 部署级 E2E（确定性 Workflow 的自定义 REST 流式出口，社区 issue #151）：
# 起零中间事件的 Workflow → POST stream=true → 断言终答帧真的到了客户端。
# exit 0=过 / 1=不过。
#
# ## 它的来历
#
# 2026-08-25 先于修复入库，放在 `deploy-e2e/pending/`——`tools/deploy_e2e_guard.py` 只收
# 顶层脚本，红脚本入库而不拖红整套门禁，等于部署级的 strict xfail。当时在旧代码上实跑
# 两次均退 1，红在实验组「0 字节空流」、对照组绿。修复那一步
# （`internal/ledger/CHANGE-PLAN-QUERYCHUNK-TERMINAL.md` 第 6 步）把它移进顶层，
# 从那一刻起由门禁常态考核。
#
# ## 它验的那一条
#
# 权威 FEAT-022 两条 MUST：「必须支持以 SSE 返回执行过程，事件语义必须可追溯到
# 标准 Agent Task / output / terminal / error 语义」「每帧必须能对应标准输出、进度、
# 终态、中断或错误语义」；另有「不应无语义地裸断连接」。
# 0 字节空流三条全违。上游 Java `CustomRestSseTransport.onNext` 每个事件都投，
# 投影产出为空时按 `adapter_execution_failed` 抛 500——上游把「投影产出空」当故障。
#
# ## 断言
#
# 三项一起验，缺一即不算修好：
#   一、出流形态是 SSE（Content-Type）——退化成普通 JSON 也是错
#   二、至少一个 `data:` 帧——0 字节空流正是缺陷本体
#   三、有一帧是终答帧（存量的事件名 `final_answer_chunk`），且正文含本次 query
#       ——只断言「有帧」会被任何一帧买通；只断言事件名会被空正文买通
#
# 期望值来源：query 由脚本自己生成并原样回显（工作流是直通的），不依赖任何模型输出；
# 事件名取存量（`.legacy-oracle/applications/a2a_service/api/dispatch.py` 以它为
# 首选答案来源），也是台账「唯一允许的对外变化」写死的那一个名字。
#
# 对照组：同一会话 stream=false 必须拿到非空 answer——证明工作流本身跑通了，
# 红的是流式出口这一层，不是执行链路。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-workflow-stream] $*"; }

e2e_start "e2e_workflow_stream_server" 18117
e2e_wait_health 60

CONV="conv-wfs-$$"
QUERY="查余额-wfs-$$"
AGENT_PATH="$BASE/v1/proj/agents/edp_workflow_stream_e2e/conversations/$CONV"

log "1/2 对照组：stream=false 必须拿到非空 answer（证明工作流本身跑通）"
R0=$(curl -s -m 30 "$AGENT_PATH" -H "Content-Type: application/json" \
  -d "{\"input\":{\"query\":\"$QUERY\"},\"stream\":false}")
R0="$R0" QUERY="$QUERY" python3 -c "
import json, os
d = json.loads(os.environ['R0'] or '{}')
assert d.get('answer'), f'对照组 answer 为空，工作流没跑通，本脚本无从判：{os.environ[\"R0\"][:200]}'
assert os.environ['QUERY'] in json.dumps(d, ensure_ascii=False), f'对照组 answer 不含 query 回显：{os.environ[\"R0\"][:200]}'
" || { log "❌ 对照组失败——执行链路本身有问题，不是本脚本要验的那一层"; e2e_diag; exit 1; }
log "   ✅ 对照组通过：阻塞式拿到了终答"

log "2/2 实验组：stream=true，终答帧必须到客户端"
OUT=$(curl -s -i -N --max-time 30 "$AGENT_PATH" -H "Content-Type: application/json" \
  -d "{\"input\":{\"query\":\"$QUERY\"},\"stream\":true}" 2>/dev/null | head -c 20000 || true)

OUT="$OUT" QUERY="$QUERY" python3 -c "
import json, os

raw = os.environ['OUT']
head, _, body = raw.partition('\r\n\r\n')
if not body:
    head, _, body = raw.partition('\n\n')

# 一、出流形态
assert 'text/event-stream' in head.lower(), f'不是 SSE 出口，响应头：{head[:300]}'

# 二、至少一帧——0 字节空流正是缺陷本体
frames = [l[6:] for l in body.splitlines() if l.startswith('data: ')]
assert frames, (
    'AssertionError: 0 字节空流——HTTP 200、Content-Type 是 SSE、一个 data 帧都没有。'
    '确定性 Workflow 没有中间事件，终答被投影层当完成信号吞掉（issue #151）')

# 三、终答帧到了，且正文含本次 query
finals = []
for f in frames:
    try:
        env = json.loads(f or '{}')
    except json.JSONDecodeError:
        continue
    inner = env.get('custom_rsp_data') or {}
    if inner.get('event') == 'final_answer_chunk':
        finals.append(inner.get('content') or '')
assert finals, f'有 {len(frames)} 帧但没有 final_answer_chunk 终答帧：' + ' | '.join(x[:120] for x in frames[:3])
assert any(os.environ['QUERY'] in c for c in finals), f'终答帧正文不含本次 query：{finals[:2]}'
print(f'   帧数 {len(frames)}，终答帧 {len(finals)}，正文回显 query 成立')
" || { log "❌ 流式出口断言不成立"; echo "$OUT" | head -c 600; echo; e2e_diag; exit 1; }

log "✅ 确定性 Workflow 的流式出口：终答帧到达客户端"
exit 0
