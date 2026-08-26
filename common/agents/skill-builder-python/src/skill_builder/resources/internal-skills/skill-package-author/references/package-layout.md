# Skill 包目录规范

最终 `generated-skill/` 是交付给其他 Agent 使用的能力包。

## 推荐结构

```text
generated-skill/
├── SKILL.md
├── requirements.txt   # 可选；Python 非标准依赖清单
├── package.json       # 可选；Node/TS 非标准依赖清单
├── references/
├── scripts/
├── fixtures/
└── assets/            # 可选
```

只创建有内容和用途的目录，不要创建空目录凑结构。

## 目录用途

- `SKILL.md`：核心触发条件、工作流、资源导航和关键边界。
- `agents/openai.yaml`：由宿主适配层管理，不属于 Author 的生成或修改范围。
- `requirements.txt` / `package.json` / `pyproject.toml`：只有 `scripts/` 运行时需要非标准依赖时才创建；内容必须和脚本 import 一致。
- `references/`：稳定业务知识、schema、外部系统说明、输出模板、风险和人工复核条件。
- `scripts/`：确定性校验、转换、采集、评分、计算、报告渲染。
- `fixtures/`：合成或最小可运行样例、边界样例、预期输出说明。
- `assets/`：运行时真正需要的模板、图片、字体、图标、静态工程骨架。

导出后，Skill 包根目录就是 `SKILL.md` 所在目录。面向最终使用者的命令必须使用 `scripts/...`、`fixtures/...`、`references/...`，不要使用平台 Session 内部路径 `generated-skill/...`。

`scripts/` 不用于保存当前生成会话的临时探测代码。外部网页、API、内网入口、第三方系统等交互流程默认写成 SOP、前置条件、异常处理和复核边界；只有导出后的 Skill 明确需要运行时自动化外部采集或受控操作时，才放相应运行脚本。

## 运行依赖

如果 `scripts/` 导入 Python 标准库以外的包，或 Node/TypeScript 包，必须满足至少一项：

- 在 `generated-skill/requirements.txt`、`generated-skill/pyproject.toml`、`generated-skill/package.json` 等根目录依赖清单中声明。
- 在 `references/runtime-setup.md` 中写清安装命令、运行命令和环境前置条件，并在 `SKILL.md` 资源导航中引用。

依赖必须写在导出包自己的清单或运行说明中，不能依赖当前生成会话的临时文件。

## 避免辅助文档

不要在 Skill 包里创建 `README.md`、`INSTALLATION_GUIDE.md`、`QUICK_REFERENCE.md`、`CHANGELOG.md`、运行日志或本次生成摘要。需要 Agent 执行时读取的稳定知识应进入 `references/`，需要机器使用的样例应进入结构化 fixture 文件。

## 禁止放入 generated-skill

- `validation/**`
- `playwright/**`
- `workspace/**`
- `inputs/**`
- `.pi/**`
- `__pycache__/`、`.pyc`、`node_modules/`
- 本次 Session 的截图、trace、录屏、采集输出、下载文件、临时报表、平台验收结果。

## 导出边界

导出的运行版 Skill 包默认只包含：

- `SKILL.md`
- 宿主适配层提供的 `agents/openai.yaml`（如存在；Author 不生成）
- 根目录运行依赖清单：`requirements.txt`、`pyproject.toml`、`package.json`、lockfile 等（如存在）
- `references/**`
- `scripts/**`
- `fixtures/**`
- `assets/**`

导出时会过滤日志和常见临时文件。若某文件只用于描述本次生成过程，它不属于最终运行版 Skill 包；只有另一个 Agent 执行 Skill 时必须读取、运行或复制的文件，才应进入 `generated-skill/`。

`SKILL.md` 和 `references/` 也不要引用上述禁止目录；场景摘要留在 `validation/scenario_summary.md`，不作为运行包依赖。
