#!/usr/bin/env bash
# 容器级部署 E2E（FEAT-009 端侧工具面承接，确定性无 LLM）：build → run（host 网络）→
# 四个面各打一次真 socket 往返 → 断言 → teardown。exit 0=过 / 1=不过。
#
# **本条测不到的那一面（实测确认，不是推断）**：请求级归属校验。
# 变异 `client_tool_rail.py` 的 `_belongs_to_current_request` 调用点（模型调用侧）
# 后本脚本**照样全过**——单会话往返里归属恒真，这道防线不被触及。
# 它由 `agent_runtime/tests/test_client_tool_rail.py` 的两条并发判据覆盖
# （`asyncio.gather` + 会合点，同名与不同名各一）。
# **写在这里是为了不让「E2E 全过」被读成「这一面也验过了」。**
#
# 与 run-client-tool.sh 的分工：那条打自定义 REST 面上的**投影与续接**；
# 本条打标准 A2A 面上的**工具面承接**（`clientTools`）——该能力在自定义 REST 入口
# 显式不承接（详设 §2.2），故必须在这条入口上打。
#
# 期望值来源：工具名、次序、投影内容全部**取自本次实跑返回的报文**，脚本只做结构断言，
# 不预置任何业务字面量。唯一预置的是协议错误码 -32602（JSON-RPC 规范值）。
set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_backend.sh"

log() { echo "[e2e-ct-view] $*"; }

e2e_start "e2e_client_tool_view_server" 18097
e2e_wait_health 60

A2A="$BASE/a2a/"
CONV="conv-ctv-$$"

send() {
  # $1 = metadata JSON 片段
  curl -s -m 60 "$A2A" -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":\"e2e-1\",\"method\":\"SendMessage\",\"params\":{
          \"message\":{\"messageId\":\"m-$$\",\"contextId\":\"$CONV\",\"role\":\"ROLE_USER\",
                       \"parts\":[{\"text\":\"读一下账单\"}]},
          \"metadata\":$1}}"
}

log "1/4 带 clientTools 的调用 → 工具面到达、次序、投影、能力表零污染"
R1=$(send '{"clientTools":[{"name":"readCurrentPage","description":"读当前页"},{"name":"confirmAction","description":"确认"}]}')
echo "$R1" | python3 -c "
import sys, json
body = json.load(sys.stdin)
assert body.get('error') is None, f'合法调用被拒：{body}'
task = (body.get('result') or {}).get('task') or {}
parts = ((task.get('status') or {}).get('message') or {}).get('parts') or []
artifacts = task.get('artifacts') or []
text = ' '.join(p.get('text','') for p in parts)
for a in artifacts:
    for p in (a.get('parts') or []):
        text += ' ' + p.get('text','')
start = text.find('{')
assert start != -1, f'报文里没有承接结果：{text[:300]}'
report = json.loads(text[start:text.rfind('}')+1])

face = report['tool_face']
assert face == ['serverSearch','readCurrentPage','confirmAction'], f'工具面或次序不对：{face}'
assert report['ability_manager'] == [], f'能力管理器被污染：{report[\"ability_manager\"]}（FEAT-009:80）'
proj = report['projected']
assert len(proj) == 1 and proj[0]['client_tool'] == 'readCurrentPage', f'工具调用没被投影：{proj}'
assert proj[0]['args'] == {'selector':'#bill'}, f'投影丢了模型给的入参：{proj[0]}'
assert proj[0]['call_id'], '投影缺调用标识——结果无从回填'
assert report['tool_result'] == '账单：本月消费 1280.00 元', f'客户端结果没回填成工具结果：{report[\"tool_result\"]}'
assert report['tool_face_after_close'] == ['serverSearch'], f'请求收尾后轨没摘掉：{report[\"tool_face_after_close\"]}'
print('[e2e-ct-view]    ✅ 工具面 %s；投影 %s；结果回填；能力表空；收尾复原' % (face, proj[0]['client_tool']))
" || { log "❌ 面 1-3 断言失败"; echo "$R1" | head -3; e2e_diag; exit 1; }

log "2/4 不带 clientTools 的调用 → 零影响（存量客户端走这条）"
R2=$(send '{}')
echo "$R2" | python3 -c "
import sys, json
body = json.load(sys.stdin)
assert body.get('error') is None, f'不带工具面的调用被拒：{body}'
text = json.dumps(body, ensure_ascii=False)
start = text.find('tool_face')
assert 'serverSearch' in text, '服务端工具面丢了'
assert 'readCurrentPage' not in text, '没声明的工具出现在工具面里'
print('[e2e-ct-view]    ✅ 未携带工具面即零影响')
" || { log "❌ 零影响断言失败"; echo "$R2" | head -3; e2e_diag; exit 1; }

log "3/4 clientTools 传非数组 → 参数错误（-32602），不是内部错误、不是静默忽略"
R3=$(send '{"clientTools":"not-a-list"}')
echo "$R3" | python3 -c "
import sys, json
body = json.load(sys.stdin)
err = body.get('error') or {}
assert err.get('code') == -32602, f'形态不合法未回参数错误：{body}'
assert err.get('code') != -32603, '回成了内部错误——调用方会以为是服务端故障而反复重试'
print('[e2e-ct-view]    ✅ 形态不合法回 -32602：%s' % (err.get('message') or '')[:60])
" || { log "❌ 参数错误断言失败"; echo "$R3" | head -3; e2e_diag; exit 1; }

log "4/4 视图内重名 → 同样是参数错误"
R4=$(send '{"clientTools":[{"name":"dup"},{"name":"dup"}]}')
echo "$R4" | python3 -c "
import sys, json
err = (json.load(sys.stdin).get('error') or {})
assert err.get('code') == -32602, f'重名未回参数错误：{err}'
print('[e2e-ct-view]    ✅ 视图内重名回 -32602')
" || { log "❌ 重名断言失败"; echo "$R4" | head -3; e2e_diag; exit 1; }

log "✅ 容器级 FEAT-009 端侧工具面承接 E2E 通过"
exit 0
