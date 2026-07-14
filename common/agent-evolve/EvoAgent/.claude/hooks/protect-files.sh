#!/bin/bash
# protect-files.sh — 阻止编辑生成文件和环境目录
INPUT=$(cat)
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

PROTECTED_PATTERNS=(
  ".venv/"
  "workspace/"
  "__pycache__/"
  ".pytest_cache/"
  ".mypy_cache/"
  ".ruff_cache/"
  "dist/"
  "*.egg-info/"
)

for pattern in "${PROTECTED_PATTERNS[@]}"; do
  if [[ "$FILE_PATH" == *"$pattern"* ]]; then
    case "$pattern" in
      ".venv/")
        echo "Blocked: $FILE_PATH is inside .venv/. Do not edit virtual environment files." >&2
        ;;
      "workspace/")
        echo "Blocked: $FILE_PATH is inside workspace/. This is optimization output, not source." >&2
        ;;
      "__pycache__/")
        echo "Blocked: $FILE_PATH is inside __pycache__/. Do not edit cached bytecode." >&2
        ;;
      "dist/"|"*.egg-info/")
        echo "Blocked: $FILE_PATH is a build artifact. Run 'make install' to regenerate." >&2
        ;;
      *)
        echo "Blocked: $FILE_PATH matches protected pattern '$pattern'." >&2
        ;;
    esac
    exit 2
  fi
done

exit 0
