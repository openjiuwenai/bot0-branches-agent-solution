#!/bin/bash
# loop-forest 冒烟脚本 v1（R1-处置：用户验收令"脚本冒烟效果要实际测到数值和收益"）
#
# 两档：
#   A 档（默认，零 LLM/零网络）——SmokeMetricsTest 确定性合成场景，
#      grep [smoke] 数值行（拦截数/剪枝数/预算拒绝/森林结构/真源归属/时延）
#   B 档（DEEPSEEK_API_KEY+BASE_URL 存在时自动加跑）——LoopForestAgent
#      统一入口真 LLM 冒烟（FOREST-OK 判定）
#
# 用法：bash smoke.sh [--a-only]
# 产物：logs/smoke-<ts>.log（数值留档——R1-F2 处置：不只进 console）
set -u
cd "$(dirname "$0")"
LOGS=logs
mkdir -p "$LOGS"
STAMP=$(date +%Y%m%d-%H%M%S)
LOG="$LOGS/smoke-$STAMP.log"

echo "== loop-forest smoke $STAMP ==" | tee "$LOG"

# ── A 档：确定性度量（数值+收益）──
mvn -q test -Dtest='SmokeMetricsTest' >> "$LOG" 2>&1
RC=$?
echo "[smoke.sh] A 档 rc=$RC" | tee -a "$LOG"
[ $RC -ne 0 ] && { echo "[smoke.sh] FAIL：A 档测试失败"; exit 1; }

# 数值行提取与断言（缺一行即失败——防"跑了但没测到数值"）
echo "== 实测数值（收益面）==" | tee -a "$LOG"
for metric in veto convergence budget forest; do
  LINE=$(grep "\[smoke\] $metric:" "$LOG" | tail -1)
  [ -n "$LINE" ] || { echo "[smoke.sh] FAIL：$metric 数值行缺失"; exit 1; }
  echo "$LINE" | tee -a "$LOG"
done
# 承重抽查：关键收益值域（拦截>0 / 剪枝>0 / 拒绝>0 / 真源归属）
grep -q '\[smoke\] veto:.*intercepted=[1-9]' "$LOG" \
  || { echo "[smoke.sh] FAIL：veto 拦截数为 0（bait 防线未承重）"; exit 1; }
grep -q '\[smoke\] convergence:.*pruned=[1-9]' "$LOG" \
  || { echo "[smoke.sh] FAIL：convergence 剪枝数为 0"; exit 1; }
grep -q '\[smoke\] budget:.*rejected=[1-9]' "$LOG" \
  || { echo "[smoke.sh] FAIL：budget 拒绝数为 0"; exit 1; }
grep -q 'prompts_source=module_owned' "$LOG" \
  || { echo "[smoke.sh] FAIL：prompt 真源不在本模块（寄生依赖 jar）"; exit 1; }

# ── B 档：真 LLM 统一入口冒烟（env-gated 自动）──
if [ "${1:-}" != "--a-only" ] && [ -n "${DEEPSEEK_API_KEY:-}" ] \
    && [ -n "${DEEPSEEK_BASE_URL:-}" ]; then
  echo "== B 档：LoopForestAgent 真 LLM 冒烟 ==" | tee -a "$LOG"
  mvn -q test -Dtest='LoopForestAgentSmokeE2eTest' >> "$LOG" 2>&1
  RC2=$?
  echo "[smoke.sh] B 档 rc=$RC2" | tee -a "$LOG"
  grep -E 'FOREST-OK|LOOP_FOREST' "$LOG" | tail -2 | tee -a "$LOG"
  [ $RC2 -ne 0 ] && { echo "[smoke.sh] FAIL：B 档真 LLM 冒烟失败"; exit 1; }
else
  echo "[smoke.sh] B 档跳过（无 DEEPSEEK env——A 档数值已承重）" | tee -a "$LOG"
fi

echo "[smoke.sh] ALL_PASS log=$LOG" | tee -a "$LOG"
