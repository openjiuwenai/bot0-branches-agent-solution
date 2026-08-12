#!/usr/bin/env bash
# 验证 agent-client-demo 容器状态与运行结果。
#
# EXTERNAL 模式：start.sh 已等待容器退出并解析退出码；本脚本对已退出的容器
#   重新检查退出码并打印日志摘要，便于排错或 CI 二次确认。
# UI 模式：检查容器是否在运行、UI 端口是否可访问。
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

check_docker

CONTAINER="$(config_value ACD_CONTAINER agent-client-demo)"
HOST_PORT="$(config_value ACD_HOST_PORT 8080)"
RUN_MODE="$(config_value ACD_RUN_MODE EXTERNAL)"
UI_PORT="$(config_value UI_PORT 9090)"

validate_resource_name "容器" "${CONTAINER}"

if ! container_exists "${CONTAINER}"; then
    if [ "${RUN_MODE}" = "EXTERNAL" ]; then
        # EXTERNAL 模式下容器已退出且被 start.sh 清理是正常情况（验证通过时）。
        log "容器不存在（EXTERNAL 模式跑完即退出，验证通过时 start.sh 会自动清理）。"
        log "如需复跑：bash ${SCRIPT_DIR}/start.sh"
        exit 0
    fi
    die "容器不存在：${CONTAINER}"
fi

require_owned_container "${CONTAINER}" agent-client-demo

STATE="$(docker container inspect --format '{{.State.Status}}' "${CONTAINER}" 2>/dev/null || true)"
EXIT_CODE="$(container_exit_code "${CONTAINER}")"

case "${STATE}" in
    running)
        if [ "${RUN_MODE}" = "UI" ]; then
            log "UI 容器正在运行：${CONTAINER}"
            if command -v curl >/dev/null 2>&1; then
                # UI 模式用 --network host，宿主机 127.0.0.1:UI_PORT 可访问。
                if curl -fsS --max-time 5 "http://127.0.0.1:${UI_PORT}/" >/dev/null 2>&1; then
                    log "UI 端口验证通过：http://127.0.0.1:${UI_PORT}/"
                else
                    warn "UI 端口 ${UI_PORT} 无法访问。容器仍在运行，请用 docker logs ${CONTAINER} 排查。"
                fi
            else
                warn "宿主机未安装 curl，已跳过 UI 端口验证。请用浏览器打开 http://127.0.0.1:${UI_PORT}/。"
            fi
            log "查看日志：docker logs -f ${CONTAINER}"
            log "停止：bash ${SCRIPT_DIR}/stop.sh"
        else
            warn "容器仍在运行（state=running），但模式为 ${RUN_MODE}（预期已退出）。"
            warn "可能是验证耗时较长或卡住。查看日志：docker logs -f ${CONTAINER}"
        fi
        ;;
    exited|dead)
        if [ "${RUN_MODE}" = "UI" ]; then
            show_recent_logs "${CONTAINER}"
            die "UI 容器已退出（state=${STATE}, exit=${EXIT_CODE}），预期应常驻运行。"
        fi
        # EXTERNAL 模式：退出是预期行为，解析退出码。
        if [ "${EXIT_CODE}" = "0" ]; then
            log "验证通过：全部断言 PASSED（exit=0, state=${STATE}）。"
        else
            show_recent_logs "${CONTAINER}"
            die "验证失败：exit=${EXIT_CODE}, state=${STATE}。请用 docker logs ${CONTAINER} 查看完整日志。"
        fi
        ;;
    *)
        die "容器状态异常：state=${STATE}（exit=${EXIT_CODE}）。"
        ;;
esac

log "基础部署验证完成。"
