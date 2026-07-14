#!/bin/bash
# block-dangerous-git.sh — 阻止危险的 git 操作
INPUT=$(cat)
CMD=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

DANGEROUS_RULES=(
  "git reset --hard::(^|[[:space:];|&])git[[:space:]]+reset[[:space:]]+--hard($|[[:space:];|&])"
  "git clean -fd/-xdf::(^|[[:space:];|&])git[[:space:]]+clean[[:space:]]+-[^[:space:];|&]*f[^[:space:];|&]*d|(^|[[:space:];|&])git[[:space:]]+clean[[:space:]]+-[^[:space:];|&]*d[^[:space:];|&]*f"
  "git push --force::(^|[[:space:];|&])git[[:space:]]+push[[:space:]].*(--force|--force-with-lease|-f)($|[[:space:];|&])"
  "git branch -D::(^|[[:space:];|&])git[[:space:]]+branch[[:space:]]+-D($|[[:space:];|&])"
  "git push --delete::(^|[[:space:];|&])git[[:space:]]+push[[:space:]].*(--delete|:[^[:space:];|&]+)"
  "git stash drop/clear::(^|[[:space:];|&])git[[:space:]]+stash[[:space:]]+(drop|clear)($|[[:space:];|&])"
)

for rule in "${DANGEROUS_RULES[@]}"; do
  name=${rule%%::*}
  pattern=${rule#*::}
  if echo "$CMD" | grep -Eq "$pattern"; then
    echo "Blocked: '$name' is forbidden. Dangerous git operations require human confirmation." >&2
    exit 2
  fi
done

exit 0
