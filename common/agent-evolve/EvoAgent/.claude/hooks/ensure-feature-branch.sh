#!/bin/bash
# ensure-feature-branch.sh — prevent Claude from editing or committing on main.
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

CURRENT_BRANCH=$(git -C "$CLAUDE_PROJECT_DIR" branch --show-current 2>/dev/null)

if [ "$CURRENT_BRANCH" != "main" ]; then
  exit 0
fi

case "$TOOL_NAME" in
  Edit|Write|MultiEdit)
    echo "Blocked: current branch is main. Create a feature branch before editing files, for example: git switch -c chore/<short-topic>." >&2
    exit 2
    ;;
  Bash)
    if echo "$CMD" | grep -Eq '(^|[[:space:];|&])git[[:space:]]+commit($|[[:space:];|&])'; then
      echo "Blocked: current branch is main. Commit from a feature branch, not main." >&2
      exit 2
    fi
    ;;
esac

exit 0
