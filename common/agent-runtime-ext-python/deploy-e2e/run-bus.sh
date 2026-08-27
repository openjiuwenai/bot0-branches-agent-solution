#!/usr/bin/env bash
# TC-017-028 · 部署级 E2E（FEAT-017 总线事件订阅消费，确定性无 LLM）：
# build → run（真端口）→ 注入事件 → 断言投影与确认 → teardown。exit 0=过 / 1=不过。
#
# **为什么这条不可省**：前五片判据全部在进程内构造——信封是字典、投递是替身、
# 标准入口是替身 handler。它们测不到「装配起来的这套东西在一个真实进程里跑得起来」。
# 本仓有实证：FEAT-027 的投射轨 65 条判据全绿而产品代码里零构造，装配点没传，
# 整条链路在生产中一帧都不产生。
#
# **本条驱动的是真实装配链路**：真 uvicorn、真端口、真 HTTP 栈、真 a2a-sdk 的
# `RequestHandler`。被替掉的只有 broker 那一跳（事件经 HTTP 端点注入），
# 而 broker 接线归宿主与 agent-bus——权威 `FEAT-017:58` 明禁 runtime 依赖 broker 细节。
#
# **本条测不到的那一面**（实测确认，不是推断）：broker 的重投、死信与去重协议。
# 它们由宿主的投递适配层承担；本 runtime 侧只承诺「按结论调对确认或退回」，
# 那一件由 `agent_runtime/tests/test_bus_adapters.py` 的 `TestConsumerLoop` 覆盖。
# **写在这里是为了不让「E2E 全过」被读成「broker 那一跳也验过了」。**
#
# 期望值来源：事件类型、任务标识、流引用全部**取自本次实跑返回的报文**，
# 脚本只做结构断言与关联断言，不预置任何业务字面量。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-bus] $*"; }

TENANT="t-e2e-$$"
# **租户经环境变量传进容器**：闸门与信封校验都要知道本 runtime 服务哪个租户。
# 不传则两者都退化为「不校验」，而那时 E2E 全绿也证明不了这两条防线在生效。
export E2E_BUS_TENANT="$TENANT"
E2E_PASS_ENV="E2E_BUS_TENANT"
e2e_start "e2e_bus_server" 18099
e2e_wait_health 60

CORR="corr-$$"

inject() {
  curl -s -m 30 "$BASE/bus/inject" -H "Content-Type: application/json" -d "$1" > /dev/null
}

log "1/4 创建类事件 → 建 Task、发接受投影、确认消费"
inject "{\"schemaVersion\":\"1.0\",\"eventType\":\"CLIENT_INVOCATION_REQUESTED\",
  \"messageId\":\"m-create\",\"tenantId\":\"$TENANT\",\"sourceServiceId\":\"gateway\",
  \"targetServiceId\":\"e2e-bus-runtime\",\"correlationId\":\"$CORR\",\"traceId\":\"tr-1\",
  \"idempotencyKey\":\"idem-1\",\"deadline\":4000000000,
  \"inlinePayload\":\"{\\\"method\\\":\\\"SendMessage\\\",\\\"params\\\":{\\\"message\\\":{\\\"parts\\\":[{\\\"text\\\":\\\"查账单\\\"}]}}}\"}"

log "2/4 同幂等键重投 → 不建第二个 Task、补发等价接受投影"
inject "{\"schemaVersion\":\"1.0\",\"eventType\":\"CLIENT_INVOCATION_REQUESTED\",
  \"messageId\":\"m-replay\",\"tenantId\":\"$TENANT\",\"sourceServiceId\":\"gateway\",
  \"targetServiceId\":\"e2e-bus-runtime\",\"correlationId\":\"$CORR\",\"traceId\":\"tr-1\",
  \"idempotencyKey\":\"idem-1\",\"deadline\":4000000000,
  \"inlinePayload\":\"{\\\"method\\\":\\\"SendMessage\\\",\\\"params\\\":{\\\"message\\\":{\\\"parts\\\":[{\\\"text\\\":\\\"查账单\\\"}]}}}\"}"

log "3/4 目标不是本 runtime → 拒绝投影"
inject "{\"schemaVersion\":\"1.0\",\"eventType\":\"CLIENT_INVOCATION_REQUESTED\",
  \"messageId\":\"m-wrong-target\",\"tenantId\":\"$TENANT\",\"sourceServiceId\":\"gateway\",
  \"targetServiceId\":\"someone-else\",\"correlationId\":\"$CORR\",\"traceId\":\"tr-1\",
  \"idempotencyKey\":\"idem-2\",\"deadline\":4000000000,
  \"inlinePayload\":\"{\\\"method\\\":\\\"SendMessage\\\",\\\"params\\\":{\\\"message\\\":{\\\"parts\\\":[]}}}\"}"

log "4/5 别的租户的事件 → 拒绝消费（FEAT-017:56／:164）"
# **这一步只有配了租户才有意义**：不配时信封校验退化为「租户非空即可」，
# 任何租户的事件都会被正常消费、建出 Task，而对外表现是「一切正常」。
inject "{\"schemaVersion\":\"1.0\",\"eventType\":\"CLIENT_INVOCATION_REQUESTED\",
  \"messageId\":\"m-other-tenant\",\"tenantId\":\"t-someone-else\",\"sourceServiceId\":\"gateway\",
  \"targetServiceId\":\"e2e-bus-runtime\",\"correlationId\":\"$CORR\",\"traceId\":\"tr-1\",
  \"idempotencyKey\":\"idem-3\",\"deadline\":4000000000,
  \"inlinePayload\":\"{\\\"method\\\":\\\"SendMessage\\\",\\\"params\\\":{\\\"message\\\":{\\\"parts\\\":[{\\\"text\\\":\\\"越权\\\"}]}}}\"}"

log "5/6 触发等待输入 → INPUT_REQUIRED 投影必须带得出「等什么」"
# 权威 FEAT-017:79 要四项：taskId、输入需求描述、correlation、可恢复上下文引用。
# **通路通不等于载荷不空**：一度这条投影只带前两项，调用方知道它在等、
# 却不知道等的是什么，也拿不到续接锚点——只能再去查一次 Task，
# 而那正是 FEAT-017:196 禁止的形态换了个位置发生。
inject "{\"schemaVersion\":\"1.0\",\"eventType\":\"CLIENT_INVOCATION_REQUESTED\",
  \"messageId\":\"m-need-input\",\"tenantId\":\"$TENANT\",\"sourceServiceId\":\"gateway\",
  \"targetServiceId\":\"e2e-bus-runtime\",\"correlationId\":\"$CORR\",\"traceId\":\"tr-1\",
  \"idempotencyKey\":\"idem-need-input\",\"deadline\":4000000000,
  \"inlinePayload\":\"{\\\"method\\\":\\\"SendMessage\\\",\\\"params\\\":{\\\"message\\\":{\\\"parts\\\":[{\\\"text\\\":\\\"我要输入\\\"}]}}}\"}"

log "6/6 读回投影并断言"
STATE=$(curl -s -m 30 "$BASE/bus/projections")

echo "$STATE" | TENANT="$TENANT" CORR="$CORR" python3 -c "
import json, os, sys

state = json.load(sys.stdin)
projections = state['projections']
tenant, corr = os.environ['TENANT'], os.environ['CORR']

assert projections, f'一条投影都没有——总线消费没接上：{state}'

kinds = [p['eventType'] for p in projections]
assert 'INVOCATION_ACCEPTED' in kinds, f'接受投影没发出去：{kinds}'
assert 'INVOCATION_REJECTED' in kinds, f'拒绝投影没发出去：{kinds}'

# 接受投影：两条（首次 + 重投补发），且任务标识相同（FEAT-017:54 的创建幂等）
accepted = [p for p in projections if p['eventType'] == 'INVOCATION_ACCEPTED']
# **按调用分组数，不按总数数**：本脚本注入了两次同幂等键的创建（首次 + 重投）
# 与一次独立的等待输入调用。总数会随新增场景变，而「重投不建第二个 Task」
# 这条要求不变——**判据要盯住那条要求，不是盯住一个会随场景漂的计数**。
assert len(accepted) >= 2, f'接受投影少于两条：{len(accepted)}'
assert all(p['taskId'] for p in accepted), '接受投影缺任务标识'
replayed = [p for p in accepted if p['taskId'] == accepted[0]['taskId']]
assert len(replayed) == 2, (
    '首次与重投没有落在同一个 Task 上：' + str([p['taskId'] for p in accepted]))

# 拒绝投影不得伪造任务标识（上游 §3.3 的字段表）
rejected = [p for p in projections if p['eventType'] == 'INVOCATION_REJECTED']
for p in rejected:
    assert not p['taskId'], f'拒绝投影带了任务标识：{p}'
    assert p['errorCode'], f'拒绝投影没带可编程原因（FEAT-017:46）：{p}'

# 关联字段原样带回（FEAT-017:45）
for p in projections:
    assert p['tenantId'] == tenant, f'租户串了：{p}'
    assert p['correlationId'] == corr, f'correlation 没原样带回：{p}'
    assert p['traceId'], f'trace 丢了：{p}'

# **别的租户的事件不得被消费**（FEAT-017:56／:164）：它既不该建 Task，
# 也不该产生任何带那个租户的投影。这一条与「目标不匹配」是两道独立的门。
strangers = [p for p in projections if p['tenantId'] != tenant]
assert not strangers, '投影里出现了别的租户: ' + str([p['tenantId'] for p in strangers])

# 三条事件全部确认——确定性拒绝也确认（详设 §4.1）
acked = set(state['acknowledged'])
assert acked == {'m-create', 'm-replay', 'm-wrong-target', 'm-other-tenant',
                 'm-need-input'}, (
    f'确认的消息集合不对：{sorted(acked)}；退回={state[\"rejected\"]}')
assert state['rejected'] == [], f'不该有退回：{state[\"rejected\"]}'

# **窗口内跑完的调用必须补发响应投影**（FEAT-017:47）。
# 本条只能在这一层验：进程内判据用替身桥接，它带不带回响应载荷是判据自己摆的；
# 真进程里那份载荷由标准入口返回的 Task 快照派生，中间要经过
# 「终态判定 → 载荷构造 → 领域层投影守卫」三跳，任一跳断掉这条投影就消失，
# 而对外表现是「受理正常，只是调用方拿不到结果、只能去轮询」。
response = [p for p in projections if p['eventType'].endswith('_RESPONSE')]
assert response, (
    f'没有响应投影——窗口内跑完的调用没把结果带回去（FEAT-017:47）：{kinds}')
for p in response:
    assert p['taskId'], f'响应投影缺任务标识：{p}'
    assert p['tenantId'] == tenant, f'响应投影的租户不对：{p}'

# **终态投影必须出现**（FEAT-017:51）：被测 Agent 答完后 Task 进 COMPLETED，
# 包装的任务存储观察到它即发终态投影。没有它说明观察链路没接上——
# 而那时对外表现是「一切正常，只是调用方永远收不到完成通知」。
terminal = [p for p in projections if p['eventType'].endswith('_TERMINAL')]
assert terminal, (
    f'没有终态投影——Task 状态观察链路没接上（FEAT-017:51）：{kinds}')
for p in terminal:
    assert p['taskId'], f'终态投影缺任务标识：{p}'
    assert p['tenantId'] == tenant, f'终态投影的租户不对：{p}'

# **等待输入投影的字段面**（FEAT-017:79 的四项）。
waiting = [p for p in projections if p['eventType'].endswith('_INPUT_REQUIRED')]
assert waiting, f'没有等待输入投影——中断没走出去：{kinds}'
for p in waiting:
    assert p['taskId'], f'等待输入投影缺任务标识：{p}'
    assert p['correlationId'] == corr, f'等待输入投影的 correlation 不对：{p}'
    payload = p.get('inlinePayload') or {}
    assert payload, (
        f'等待输入投影不带载荷：{p}——调用方知道它在等，却不知道等的是什么')
    blob = json.dumps(payload, ensure_ascii=False)
    assert '请提供账户号' in blob, f'输入需求描述没上 wire：{blob}'
    assert 'ia-e2e-1' in blob, f'可恢复上下文引用没上 wire：{blob}'

print(f'  投影序列：{kinds}')
print(f'  等待输入投影 {len(waiting)} 条，四项字段齐备')
print(f'  响应投影 {len(response)} 条，终态投影 {len(terminal)} 条')
print('  两次接受同一个 Task：' + accepted[0]['taskId'])
print(f'  {len(acked)} 条事件全部确认，零退回')
" || { log "断言失败"; e2e_teardown; exit 1; }

log "通过"
e2e_teardown
exit 0
