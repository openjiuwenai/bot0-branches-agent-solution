#!/usr/bin/env bash
# FEAT-012 真实 Broker 验收入口（fail-closed）。
#
# 用法（从仓库根或 event-bus 目录均可，脚本按自身位置定位 pom）：
#   bash common/agent-bus/event-bus/broker-acceptance.sh 127.0.0.1:9876         # 位置参数（Windows/PowerShell 最简单，无需 env 透传）
#   ROCKETMQ_NAMESERVER=127.0.0.1:9876 bash common/agent-bus/event-bus/broker-acceptance.sh   # 环境变量
#
# 验收语义（对齐 ISSUE-80 验收标准）：
#   1. 缺少 ROCKETMQ_NAMESERVER 时立即非零退出（不得产生"验收通过"）。
#   2. 三个 RealBroker*IntegrationTest 执行数 > 0，且 Skipped=0（env 守卫未跳过）。
#   3. 任一测试失败 → 非零退出。
#
# 范围：RealBroker*IT 属组件级真实 Broker 集成切片，不等同于已部署应用间的产品端到端验收。
set -euo pipefail

# Accept the nameserver as $1, else fall back to the ROCKETMQ_NAMESERVER env var.
# Fail-closed: neither provided => non-zero exit (no false "acceptance passed").
NS="${1:-${ROCKETMQ_NAMESERVER:-}}"
: "${NS:?ROCKETMQ_NAMESERVER is required for broker acceptance — pass it as an argument (bash broker-acceptance.sh 127.0.0.1:9876) or set the ROCKETMQ_NAMESERVER env var}"
export ROCKETMQ_NAMESERVER="$NS"   # propagate to the surefire test JVM so @EnabledIfEnvironmentVariable enables the tests

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TESTS='RealBrokerProduceSideIntegrationTest,RealBrokerResponseSideIntegrationTest,RealBrokerTwoHopRelayIntegrationTest'

mvn -f "$SCRIPT_DIR/pom.xml" -pl event-bus-relay test -Dtest="$TESTS"

shopt -s nullglob
reports=( "$SCRIPT_DIR"/event-bus-relay/target/surefire-reports/TEST-*RealBroker*.xml )
shopt -u nullglob

if [ "${#reports[@]}" -eq 0 ]; then
  echo "broker-acceptance: no surefire reports found — RealBroker*IT did not execute" >&2
  exit 1
fi

if grep -qE 'skipped="[1-9][0-9]*"' "${reports[@]}"; then
  echo "broker-acceptance: one or more RealBroker*IT were SKIPPED (env guard did not pass) — acceptance failed" >&2
  grep -H 'tests=' "${reports[@]}" >&2 || true
  exit 1
fi

echo "broker-acceptance: all RealBroker*IT executed with Skipped=0"
