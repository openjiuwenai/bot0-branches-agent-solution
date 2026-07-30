#!/usr/bin/env bash
# agent-core-ext-java 冒烟测试脚本
#
# 执行 Maven 单元测试并打印格式化冒烟报告，包含：
#   - 执行总体信息（项目名、依赖、耗时、结果）
#   - 执行摘要（总数/通过/失败/跳过/通过率）
#   - 用例明细
#
# Usage:
#   ./smoke.sh                # 默认：mvn clean test + 报告
#   ./smoke.sh --skip-build   # 跳过 mvn test，仅解析已有报告
#   ./smoke.sh -f pom.xml     # 指定 POM 路径（同 mvn -f）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
POM_FILE="$ROOT/pom.xml"
SKIP_BUILD=0

# 临时文件，trap 确保退出时清理
META_JSON="$ROOT/.smoke-meta.json"
RESULTS_JSON="$ROOT/.smoke-results.json"
trap 'rm -f "$META_JSON" "$RESULTS_JSON"' EXIT

# ---- 参数解析 -----------------------------------------------------------
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    -f) shift; POM_FILE="$1"; shift || true ;;
    *) ;;
  esac
done

POM_DIR="$(dirname "$POM_FILE")"
POM_DIR="$(cd "$POM_DIR" && pwd)"

# ---- ANSI 颜色 -----------------------------------------------------------
if [[ -t 1 ]]; then
  C_RESET='\033[0m'
  C_BOLD='\033[1m'
  C_GREEN='\033[32m'
  C_RED='\033[31m'
  C_YELLOW='\033[33m'
  C_CYAN='\033[36m'
  C_DIM='\033[2m'
else
  C_RESET='' C_BOLD='' C_GREEN='' C_RED='' C_YELLOW='' C_CYAN='' C_DIM=''
fi

# ---- 辅助函数 -----------------------------------------------------------
info_header() {
  printf '%b' "${C_BOLD}"
  printf '=%.0s' $(seq 1 60)
  printf '\n  冒烟测试报告\n'
  printf '=%.0s' $(seq 1 60)
  printf '%b\n\n' "${C_RESET}"
}

section_sep() {
  printf '%b' "${C_DIM}"
  printf -- '-%.0s' $(seq 1 60)
  printf '%b\n\n' "${C_RESET}"
}

# ---- POM 元信息提取 -----------------------------------------------------
extract_text() {
  # 用 python 解析 pom.xml，输出 JSON 元信息
  python3 - "$POM_FILE" <<'PY'
import sys
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET  # 本地可信 POM，回退标准库

def ns_tag(tag):
    """匹配带或不带 maven namespace 的元素"""
    return tag

def find_text(root, path_parts):
    """按路径逐层查找，返回第一个匹配的 text"""
    # path_parts 例如 ["parent","version"] 或 ["version"]
    # 先尝试直接路径
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    expr = './' + '/'.join(f'm:{p}' for p in path_parts)
    el = root.find(expr, ns)
    if el is not None and el.text:
        return el.text.strip()
    # 回退：忽略 namespace 遍历
    current = [root]
    for part in path_parts:
        next_level = []
        for c in current:
            for child in c:
                tag_local = child.tag.split('}')[-1] if '}' in child.tag else child.tag
                if tag_local == part:
                    next_level.append(child)
        current = next_level
        if not current:
            return None
    return current[0].text.strip() if current[0].text else None

def find_texts(root, path_parts):
    """返回所有匹配节点的 text 列表"""
    current = [root]
    for part in path_parts:
        next_level = []
        for c in current:
            for child in c:
                tag_local = child.tag.split('}')[-1] if '}' in child.tag else child.tag
                if tag_local == part:
                    next_level.append(child)
        current = next_level
        if not current:
            return []
    return [c.text.strip() for c in current if c.text and c.text.strip()]

pom_file = sys.argv[1]
root = ET.parse(pom_file).getroot()

import re

def resolve_properties(root):
    """从 POM 提取 <properties> 并解析 ${...} 引用"""
    props = {}
    for child in root:
        tag_local = child.tag.split('}')[-1] if '}' in child.tag else child.tag
        if tag_local == 'properties':
            for prop in child:
                ptag = prop.tag.split('}')[-1] if '}' in prop.tag else prop.tag
                if prop.text:
                    props[ptag] = prop.text.strip()
    # 解析 ${...} 引用（最多 3 层，避免循环）
    for _ in range(3):
        for k, v in list(props.items()):
            m = re.match(r'^\$\{(.+)\}$', v)
            if m and m.group(1) in props:
                props[k] = props[m.group(1)]
    return props

def resolve_value(val, props):
    """解析值中的 ${...} 占位符"""
    if not val:
        return val
    return re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m.group(1), m.group(0)), val)

props = resolve_properties(root)

# 项目名和版本
artifact_id = find_text(root, ['artifactId'])
version = resolve_value(find_text(root, ['version']), props)
name_el = find_text(root, ['name'])
name = name_el if name_el else artifact_id

# 子模块
modules = find_texts(root, ['modules', 'module'])

# 核心依赖 (dependencyManagement → dependencies → dependency)
deps = []
dm = None
# 找 dependencyManagement
for child in root:
    tag_local = child.tag.split('}')[-1] if '}' in child.tag else child.tag
    if tag_local == 'dependencyManagement':
        dm = child
        break

if dm is not None:
    for child in dm:
        tag_local = child.tag.split('}')[-1] if '}' in child.tag else child.tag
        if tag_local == 'dependencies':
            for dep in child:
                dep_tag = dep.tag.split('}')[-1] if '}' in dep.tag else dep.tag
                if dep_tag == 'dependency':
                    gid = find_text(dep, ['groupId'])
                    aid = find_text(dep, ['artifactId'])
                    ver = resolve_value(find_text(dep, ['version']), props)
                    scope = find_text(dep, ['scope'])
                    if gid and aid:
                        v = ver if ver else '(managed)'
                        if scope and scope == 'test':
                            deps.append(f'{gid}:{aid}:{v} (test)')
                        else:
                            deps.append(f'{gid}:{aid}:{v}')

import json
print(json.dumps({
    'name': name,
    'version': version,
    'artifactId': artifact_id,
    'modules': modules,
    'deps': deps,
}))
PY
}

# ---- 测试结果解析 --------------------------------------------------------
parse_reports() {
  # 扫描 target/surefire-reports/TEST-*.xml，输出 JSON
  local base_dir="$1"
  python3 - "$base_dir" <<'PY'
import sys, os, json, glob
try:
    import defusedxml.ElementTree as ET
except ImportError:
    import xml.etree.ElementTree as ET  # 本地可信 Surefire 报告，回退标准库

base_dir = sys.argv[1]
pattern = os.path.join(base_dir, '**', 'target', 'surefire-reports', 'TEST-*.xml')
xml_files = glob.glob(pattern, recursive=True)

all_cases = []
total = 0
passed = 0
failed = 0
skipped = 0
errors = 0

for fpath in sorted(xml_files):
    try:
        tree = ET.parse(fpath)
        root = tree.getroot()
        # testcase 元素在 testsuite 下
        for tc in root.iter('testcase'):
            total += 1
            classname = tc.get('classname', 'UnknownClass')
            testname = tc.get('name', 'UnknownTest')
            time_sec = tc.get('time', '0')

            status = 'PASS'
            reason = ''
            # 检查 failure
            fail_el = tc.find('failure')
            err_el = tc.find('error')
            skip_el = tc.find('skipped')

            if fail_el is not None:
                status = 'FAIL'
                failed += 1
                msg = fail_el.get('message', '')
                text = (fail_el.text or '').strip()
                reason = msg if msg else text[:120]
            elif err_el is not None:
                status = 'ERROR'
                errors += 1
                msg = err_el.get('message', '')
                text = (err_el.text or '').strip()
                reason = msg if msg else text[:120]
            elif skip_el is not None:
                status = 'SKIP'
                skipped += 1
                msg = skip_el.get('message', '')
                reason = msg if msg else ''
            else:
                passed += 1

            all_cases.append({
                'class': classname,
                'test': testname,
                'status': status,
                'time': time_sec,
                'reason': reason[:200],
            })
    except Exception as e:
        all_cases.append({
            'class': 'PARSE_ERROR',
            'test': os.path.basename(fpath),
            'status': 'ERROR',
            'time': '0',
            'reason': str(e),
        })
        errors += 1
        total += 1

print(json.dumps({
    'total': total,
    'passed': passed,
    'failed': failed,
    'skipped': skipped,
    'errors': errors,
    'cases': all_cases,
    'file_count': len(xml_files),
}))
PY
}

# ---- 打印报告 -----------------------------------------------------------
print_report() {
  local meta_json="$1"
  local results_json="$2"

  # 解析 JSON
  local proj_name version artifact_id modules_json deps_json
  proj_name="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['name'])" "$meta_json")"
  version="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['version'])" "$meta_json")"
  modules_json="$(python3 -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1]))['modules']))" "$meta_json")"
  deps_json="$(python3 -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1]))['deps']))" "$meta_json")"

  local total passed failed skipped errors
  total="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['total'])" "$results_json")"
  passed="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['passed'])" "$results_json")"
  failed="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['failed'])" "$results_json")"
  skipped="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['skipped'])" "$results_json")"
  errors="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['errors'])" "$results_json")"

  local fail_total=$((failed + errors))

  # 通过率
  local pass_rate=0
  if [[ "$total" -gt 0 ]]; then
    pass_rate="$(python3 -c "print(f'{$passed / $total * 100:.2f}')")"
  fi

  # 模块列表
  local modules_str
  modules_str="$(python3 -c "
import json,sys
mods = json.loads(sys.argv[1])
print(', '.join(mods) if mods else '(无子模块)')
" "$modules_json")"

  # 依赖列表（取前 6 个）
  local deps_str
  deps_str="$(python3 -c "
import json,sys
deps = json.loads(sys.argv[1])
if len(deps) <= 6:
    print(', '.join(deps) if deps else '(无)')
else:
    print(', '.join(deps[:6]) + ' ...')
" "$deps_json")"

  # === 一、执行总体信息 ===
  info_header

  printf "  %b项目名称:%b     %s (%s)\n" "$C_BOLD" "$C_RESET" "$proj_name" "$version"
  printf "  %b子模块:%b       %s\n" "$C_BOLD" "$C_RESET" "$modules_str"
  printf "  %b执行时间:%b     %s\n" "$C_BOLD" "$C_RESET" "$START_TIME"
  printf "  %b耗时:%b         %s\n" "$C_BOLD" "$C_RESET" "$ELAPSED_STR"
  if [[ "$BUILD_OK" -eq 1 ]]; then
    printf "  %b构建结果:%b     ${C_GREEN}通过${C_RESET} (exit code: $MVN_EXIT_CODE)\n" "$C_BOLD" "$C_RESET"
  else
    printf "  %b构建结果:%b     ${C_RED}失败${C_RESET} (exit code: $MVN_EXIT_CODE)\n" "$C_BOLD" "$C_RESET"
  fi
  printf "  %b核心依赖:%b     %s\n" "$C_BOLD" "$C_RESET" "$deps_str"
  echo ""

  # === 二、执行摘要 ===
  printf '%b' "${C_BOLD}"
  printf -- '-%.0s' $(seq 1 40)
  printf '\n  执行摘要\n'
  printf -- '-%.0s' $(seq 1 40)
  printf '%b\n\n' "${C_RESET}"

  printf "  %b总用例数:%b     $total\n" "$C_BOLD" "$C_RESET"
  printf "  %b通过:%b         ${C_GREEN}$passed${C_RESET}\n" "$C_BOLD" "$C_RESET"
  if [[ "$fail_total" -gt 0 ]]; then
    printf "  %b失败:%b         ${C_RED}$fail_total${C_RESET}\n" "$C_BOLD" "$C_RESET"
  else
    printf "  %b失败:%b         $fail_total\n" "$C_BOLD" "$C_RESET"
  fi
  if [[ "$skipped" -gt 0 ]]; then
    printf "  %b跳过:%b         ${C_YELLOW}$skipped${C_RESET}\n" "$C_BOLD" "$C_RESET"
  else
    printf "  %b跳过:%b         $skipped\n" "$C_BOLD" "$C_RESET"
  fi

  # 通过率颜色
  local rate_color=""
  local rate_reset=""
  if [[ "$fail_total" -eq 0 ]]; then
    printf "  %b通过率:%b       ${C_GREEN}${pass_rate}%%${C_RESET}\n" "$C_BOLD" "$C_RESET"
  elif [[ "$fail_total" -gt 0 ]]; then
    printf "  %b通过率:%b       ${C_RED}${pass_rate}%%${C_RESET}\n" "$C_BOLD" "$C_RESET"
  fi
  echo ""

  # === 三、用例明细 ===
  if [[ "$total" -eq 0 ]]; then
    echo "  (无测试用例)"
    echo ""
    return
  fi

  printf '%b' "${C_BOLD}"
  printf -- '-%.0s' $(seq 1 40)
  printf '\n  用例明细\n'
  printf -- '-%.0s' $(seq 1 40)
  printf '%b\n\n' "${C_RESET}"

  python3 - "$results_json" "$C_GREEN" "$C_RED" "$C_YELLOW" "$C_RESET" <<'PY'
import json, sys

with open(sys.argv[1]) as f:
    data = json.load(f)

GREEN = sys.argv[2]
RED = sys.argv[3]
YELLOW = sys.argv[4]
RESET = sys.argv[5]

ICONS = {'PASS': f'{GREEN}[PASS]{RESET}', 'FAIL': f'{RED}[FAIL]{RESET}',
         'ERROR': f'{RED}[ERROR]{RESET}', 'SKIP': f'{YELLOW}[SKIP]{RESET}'}

for c in data['cases']:
    icon = ICONS.get(c['status'], c['status'])
    short_class = c['class'].split('.')[-1] if '.' in c['class'] else c['class']
    line = f"  {icon} {short_class} - {c['test']}"
    if c['reason']:
        line += f"  [{c['reason']}]"
    print(line)
PY

  echo ""
}

# ============================================================
# 主流程
# ============================================================

# 提取 POM 元信息
extract_text > "$META_JSON"

# 执行构建
START_TIME="$(date '+%Y-%m-%d %H:%M:%S %z')"
START_EPOCH=$(date +%s)
MVN_EXIT_CODE=0

if [[ "$SKIP_BUILD" -eq 1 ]]; then
  echo "[smoke] 跳过构建，仅解析已有测试报告..."
  BUILD_OK=1
else
  echo "[smoke] 执行 mvn clean test..."
  # 切换到仓库根目录，使用相对 POM 路径
  REPO_ROOT="$(cd "$ROOT/../.." && pwd)"
  POM_REL_PATH="common/agent-core-ext-java/pom.xml"
  mvn -f "$POM_REL_PATH" clean test || MVN_EXIT_CODE=$?
fi

END_EPOCH=$(date +%s)
END_TIME="$(date '+%Y-%m-%d %H:%M:%S %z')"
ELAPSED=$((END_EPOCH - START_EPOCH))
if [[ $ELAPSED -lt 60 ]]; then
  ELAPSED_STR="${ELAPSED}s"
else
  ELAPSED_STR="$((ELAPSED / 60))m $((ELAPSED % 60))s"
fi

if [[ "$MVN_EXIT_CODE" -eq 0 ]]; then
  BUILD_OK=1
else
  BUILD_OK=0
fi

# 解析测试报告
parse_reports "$ROOT" > "$RESULTS_JSON"

# 打印报告
print_report "$META_JSON" "$RESULTS_JSON"

exit "$MVN_EXIT_CODE"
