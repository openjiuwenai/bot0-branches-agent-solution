#!/usr/bin/env bash
# Beginner entry point: validate config, optionally build, start, and functionally verify the mock.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_lib.sh"

skip_build=false
case "${1-}" in
    '') ;;
    --skip-build) skip_build=true ;;
    *) die "Unknown option: $1. Supported: --skip-build" ;;
esac

load_runtime_config
export MOCK_ENV_FILE="${ENV_FILE}"

if [ "${skip_build}" = "false" ]; then
    bash "${DEPLOY_DIR}/build-image.sh"
else
    info "Skipping image build."
fi
bash "${DEPLOY_DIR}/start.sh"
bash "${DEPLOY_DIR}/verify.sh"

info "Mock deployment completed."
