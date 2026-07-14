#!/bin/bash
# check-file-length.sh — 文件长度软约束预警（Write 时检测）
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
CONTENT=$(echo "$INPUT" | jq -r '.tool_input.content // empty')

# 跳过生成文件和缓存
if [[ "$FILE_PATH" == *".venv"* ]] || \
   [[ "$FILE_PATH" == *"__pycache__"* ]] || \
   [[ "$FILE_PATH" == *"workspace/"* ]] || \
   [[ "$FILE_PATH" == *"dist/"* ]]; then
  exit 0
fi

LINE_COUNT=$(echo "$CONTENT" | wc -l)

# Python 源码 ≤500 行
if [[ "$FILE_PATH" == *.py ]]; then
  if [ "$LINE_COUNT" -gt 500 ]; then
    echo "Warning: $(basename "$FILE_PATH") has $LINE_COUNT lines (soft limit: 500). Consider splitting by responsibility per docs/rules/code-style.md." >&2
  elif [ "$LINE_COUNT" -gt 300 ]; then
    echo "Note: $(basename "$FILE_PATH") has $LINE_COUNT lines. Consider splitting if it grows further." >&2
  fi
fi

exit 0
