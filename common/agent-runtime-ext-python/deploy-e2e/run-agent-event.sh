#!/usr/bin/env bash
# TC-027-016 · 容器级部署 E2E（FEAT-027 多跳事件投射，确定性无 LLM）：build → run（host 网络）→
# 一次真 socket 往返 → 断言三类事件的 wire 形态 → teardown。exit 0=过 / 1=不过。
#
# **为什么这条不可省**：`agentEvent` 挂在 `Artifact.metadata` 上，单元判据在 protobuf
# 序列化之前就断言完了。本仓有实证：单测全绿而 wire 契约仍有三处缺陷
# （缺 ROLE_USER、终态丢终答、端侧工具投影丢空串）——它们都是走完真往返才现形的。
#
# **本条驱动的是真实编排链路**：被测服务只产一个委派中断帧，其余交给编排层与批次执行器——
# 成员帧经编排层的转发闭包重构成对外帧，标签在那一步被带进 metadata。
# 上一版由被测服务自己收帧再 yield，绕开了那一段：把生产转发通道整个禁用后本条照样通过
# （独立复核实测出的假绿）。现在同一变异下本条如实转红。
#
# **本条测不到的那一面（实测确认，不是推断）**：多跳链路上的逐跳保留。
# 它由 agent_runtime/tests/test_agent_event_rail.py 的 TestMultiHopPreservation 覆盖
# （两跳链路串起来验：终点标签仍指向 C1、身份两跳不变、每跳各自生成委派边）。
# 本条只有 A→B 一跳，C1 的标签经 B1 透传这条路径不被触及；它由
# `agent_runtime/tests/test_agent_event_rail.py` 的保留分支判据覆盖。
# **写在这里是为了不让「E2E 全过」被读成「多跳这一面也验过了」。**
#
# 期望值来源：事件类型、来源标识、外层任务标识全部**取自本次实跑返回的报文**，
# 脚本只做结构断言与维度分离断言，不预置任何业务字面量。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-agent-event] $*"; }

e2e_start "e2e_agent_event_server" 18098
e2e_wait_health 60

A2A="$BASE/a2a/"
CONV="conv-ae-$$"

log "1/1 发起一次远端委派 → 三类事件的 wire 形态"
R1=$(curl -s -m 60 "$A2A" -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":\"e2e-1\",\"method\":\"SendMessage\",\"params\":{
        \"message\":{\"messageId\":\"m-$$\",\"contextId\":\"$CONV\",\"role\":\"ROLE_USER\",
                     \"parts\":[{\"text\":\"查一下本月账单\"}]}}}")

echo "$R1" | python3 -c "
import sys, json

body = json.load(sys.stdin)
assert body.get('error') is None, f'调用被拒：{body}'
task = (body.get('result') or {}).get('task') or {}
artifacts = task.get('artifacts') or []
assert artifacts, f'一个产物都没有：{json.dumps(task, ensure_ascii=False)[:400]}'

# 取每个产物的 metadata.agentEvent —— 位置本身就是要验的东西（FEAT-027:102）
events = [a['metadata']['agentEvent'] for a in artifacts
          if isinstance(a.get('metadata'), dict) and 'agentEvent' in a['metadata']]
assert events, ('产物上一个 agentEvent 都没有——标签没到 wire。'
                f'实收 metadata：{[a.get(\"metadata\") for a in artifacts]}')

kinds = [e.get('type') for e in events]
assert 'delegation' in kinds, f'委派边没到 wire：{kinds}'
assert 'output' in kinds, f'生产者标签没到 wire：{kinds}'
assert 'status' in kinds, f'状态投射没到 wire：{kinds}'
assert kinds[0] == 'delegation', f'委派边不是第一帧（FEAT-027:121）：{kinds}'

# 每个事件的 source 两键齐全且非空（FEAT-027:209）
for e in events:
    src = e.get('source') or {}
    assert set(src) == {'agentId', 'taskId'}, f'source 键集不对（应只有两键）：{src}'
    assert src['agentId'] and src['taskId'], f'source 有空值：{src}'

# delegation 独有 target，output 与 status 不带它（FEAT-027:96-98 的字段适用性）
deleg = next(e for e in events if e['type'] == 'delegation')
assert set(deleg) == {'type', 'source', 'target'}, f'delegation 键集不对：{sorted(deleg)}'
for e in events:
    if e['type'] == 'output':
        assert set(e) == {'type', 'source'}, f'output 多带了字段：{sorted(e)}'
    if e['type'] == 'status':
        assert set(e) == {'type', 'source', 'state'}, f'status 键集不对：{sorted(e)}'
        assert e['state'] == e['state'].lower(), f'状态名没归一：{e[\"state\"]}'

# Artifact identity 到达 wire（FEAT-027:37）——委派边与投射输出各有构造规则，
# 每跳换随机值会让客户端跨跳的去重与拼接做不成
ids = [a.get('artifactId') or a.get('artifact_id') or '' for a in artifacts]
assert any(i.startswith('delegation:') for i in ids), (
    f'委派边的产物标识没到 wire（应形如 delegation:<父>:<子>）：{ids}')
assert any(i.startswith('remote:') for i in ids), (
    f'投射输出的产物标识没到 wire（应形如 remote:<agentId>:<taskId>:<原 id>）：{ids}')

# 两个 taskId 是两个维度，不得混淆（FEAT-027:170）
outer = task.get('id') or ''
producer = next(e for e in events if e['type'] == 'output')['source']['taskId']
assert outer, '外层任务标识为空'
assert outer != producer, (
    f'外层任务标识与生产者任务标识相同（{outer}）——两个维度被混成了一个')

print(f'  事件类型序列：{kinds}')
print(f'  外层任务 {outer} ≠ 生产者任务 {producer}')
print('  三类事件的 wire 形态全部符合 FEAT-027 §3.1')
" || { log "断言失败"; e2e_teardown; exit 1; }

log "通过"
e2e_teardown
exit 0
