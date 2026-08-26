# 测试说明

## 测试目标

目标仓只保留使用者依赖的独立包契约，不复制宿主的 HTTP、ORM、页面、对象存储和发布测试。

默认测试使用 Fake Agent Runner、mock Jiuwenbox/录屏对象和临时 workspace，不需要真实模型、外部网络或运行中的 Jiuwenbox，适合本地开发和 CI。

## 安装测试依赖

```bash
cd common/agents/skill-builder-python
python3.11 -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
```

如果同时需要生成相关依赖：

```bash
.venv/bin/python -m pip install -e '.[agent-openjiuwen-python,test]'
```

## 运行默认测试

```bash
.venv/bin/python -m pytest -q
```

当前测试共 5 个测试文件、21 个 pytest case：

| 测试文件 | 覆盖范围 |
|---|---|
| `test_public_package.py` | 公共 import、内置资源、StateStore round-trip、宿主依赖边界和通用决策归一化 |
| `test_lifecycle_and_delivery.py` | Scenario→HITL→跨 Client resume→Author→Validate→Export、`ready+warn`、receipt 失效、Repair 回滚和 retry reset |
| `test_package_safety.py` | frontmatter、名称、保留路径、软链接和导出白名单 |
| `test_agent_subprocess.py` | Agent Core 请求/事件/结果协议、结构化错误、超时和 worker 调用边界 |
| `test_host_adapters.py` | Jiuwenbox workspace/Acceptance adapter，以及录屏停止、Markdown 生成和资源释放 |

`fake_agent_worker.py` 是子进程协议测试辅助程序，不是独立测试场景。

## 运行单类测试

```bash
# 生命周期和交付
.venv/bin/python -m pytest -q tests/test_lifecycle_and_delivery.py

# 包安全
.venv/bin/python -m pytest -q tests/test_package_safety.py

# Agent Core 子进程
.venv/bin/python -m pytest -q tests/test_agent_subprocess.py

# Jiuwenbox/录屏 adapter
.venv/bin/python -m pytest -q tests/test_host_adapters.py
```

查看具体测试名和执行过程：

```bash
.venv/bin/python -m pytest -vv
```

## 构建和发行包检查

```bash
.venv/bin/python -m build
```

建议在一个干净 venv 中验证 wheel，而不是只依赖源码目录 import：

```bash
python3.11 -m venv /tmp/skill-builder-wheel-check
/tmp/skill-builder-wheel-check/bin/python -m pip install \
  dist/openjiuwen_skill_builder-0.1.0-py3-none-any.whl

cd /tmp
/tmp/skill-builder-wheel-check/bin/python -c \
  "import importlib.resources, skill_builder; \
root = importlib.resources.files('skill_builder.resources'); \
assert (root / 'internal-skills' / 'scenario-skill-builder' / 'SKILL.md').is_file(); \
print(skill_builder.__file__)"
```

该检查验证：

- 实际从 `site-packages` 导入；
- 内置 Scenario/Author Skill 资源进入 wheel；
- 新增 adapter/worker 被自动包发现；
- 不依赖当前源码工作目录。

## Opt-in 真实宿主 Smoke

真实 smoke 需要：

- 已安装 `agent-openjiuwen-python` extra；
- 已安全注入模型配置；
- Jiuwenbox `/health` 可用；
- 非敏感测试材料和全新临时 workspace。

知识型材料 smoke：

```bash
.venv/bin/python examples/host_background.py \
  --workspace /tmp/skill-builder-smoke/workspace \
  --workspace-id smoke-workspace \
  --materials examples/materials/role-governance.md \
  --skill-name sample-role-skill \
  --display-name "Sample Role Skill" \
  --description "Generate a sample Skill from generic material" \
  --output /tmp/skill-builder-smoke/sample-role-skill.zip
```

可执行脚本型材料 smoke：

```bash
.venv/bin/python examples/host_background.py \
  --workspace /tmp/skill-builder-script-smoke/workspace \
  --workspace-id script-smoke-workspace \
  --materials examples/materials/tabular-validation.md \
  --skill-name sample-tabular-validator \
  --display-name "Sample Tabular Validator" \
  --description "Generate an executable validation Skill" \
  --output /tmp/skill-builder-script-smoke/sample-tabular-validator.zip
```

真实 smoke 不进入默认 CI，因为模型输出和外部沙箱存在环境差异。知识型用例验证无 scripts 的文档/reference 交付；可执行型用例验证 Python CLI、fixture 和离线 smoke。两者共同确认宿主进程、Agent Core 子进程、Jiuwenbox、状态持久化、Acceptance 和导出能够连通。

运行后应检查：

- 最终状态及 blocker；
- 每个 Agent worker 都有完整 result；
- Jiuwenbox session 已释放；
- 没有残留 Agent worker 进程；
- 归档只包含允许的 Skill 包文件；
- 日志、事件和结果不包含密钥。

## 录屏测试

默认 `test_host_adapters.py` 使用 mock 对象验证 stop、Markdown 落盘和清理，不启动真实 Chromium。

真实录屏验证是独立 opt-in 操作：

```bash
.venv/bin/python -m pip install -e '.[recording,test]'
.venv/bin/python -m playwright install chromium
```

只允许访问批准的测试 URL。运行后清理 browser profile、storage state、截图、下载和 trace，避免保存会话敏感信息。

## 失败排查

| 失败类型 | 优先检查 |
|---|---|
| `ModuleNotFoundError: skill_builder` | 是否从项目目录运行、是否执行 editable/wheel 安装 |
| 缺少内置 Skill | `MANIFEST.in`、setuptools package-data 和 wheel 内容 |
| 子进程测试失败 | 当前 Python 是否能 import 项目、`PYTHONPATH` 是否正确、worker result/event 文件 |
| 状态恢复失败 | StateStore schema、workspace id、resume token、临时目录权限 |
| 包安全测试失败 | 软链接、嵌套 `SKILL.md`、保留路径、frontmatter |
| Jiuwenbox smoke 失败 | `/health`、URL、权限、命令超时和沙箱清理 |

不要通过放宽业务门禁、提高 Repair 次数或删除失败断言来让测试通过。测试发现公共契约变化时，应先判断是预期版本变更还是回归。
