#!/usr/bin/env bash
# 验证 adapter 容器所有权、运行状态、网络连接、Docker health 与 Agent Card。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

load_management_config
check_docker
container_id="$(assert_owned_container "${ADAPTER_CONTAINER_NAME}")"

state="$(docker container inspect --format '{{.State.Status}}' "${container_id}")"
health="$(docker container inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container_id}")"
[ "${state}" = "running" ] || die "容器未运行：state=${state}。"
[ "${health}" = "healthy" ] || die "容器未健康：health=${health}。"

networks="$(docker container inspect --format '{{range $name, $_ := .NetworkSettings.Networks}}{{println $name}}{{end}}' "${container_id}")"
grep -Fxq "${AGENT_NETWORK}" <<< "${networks}" || die "容器未接入约定网络 ${AGENT_NETWORK}。"
verify_agent_card || die "容器内部 Agent Card 请求失败。"

info "验证通过：owner=${OWNER_LABEL_VALUE}, state=${state}, health=${health}, network=${AGENT_NETWORK}。"
info "宿主机入口：http://127.0.0.1:${ADAPTER_HOST_PORT}/a2a"
info "该验证不调用 Versatile；发布验收还应执行一条真实业务冒烟请求。"
