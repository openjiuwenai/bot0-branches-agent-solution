#!/usr/bin/env bash
# fix-crlf.sh — 编辑后自动 CRLF → LF
set -euo pipefail

INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

[ -z "$FILE_PATH" ] && exit 0
[ -f "$FILE_PATH" ] || exit 0

if grep -qP '\r' "$FILE_PATH" 2>/dev/null; then
  sed -i 's/\r$//' "$FILE_PATH"
fi
