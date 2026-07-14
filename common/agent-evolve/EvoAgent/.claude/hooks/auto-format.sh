#!/bin/bash
# auto-format.sh — 编辑 Python 文件后自动 ruff format
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

if [[ "$FILE_PATH" == *.py ]] && [[ "$FILE_PATH" != *".venv/"* ]]; then
  cd "$CLAUDE_PROJECT_DIR" && uv run ruff format "$FILE_PATH" 2>/dev/null
fi

exit 0
