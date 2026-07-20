# CLAUDE.md

## 项目概述

EvoAgent — 基于 agent-core 的自进化元 Agent，封装 skill 文档自动优化能力。

核心闭环：用户下达指令 → Agent 识别意图 → 编排优化 Pipeline（Adapter rollout + skill 同步）→ 输出优化报告

## 开发环境

```bash
make install    # 安装依赖
make lint       # 代码检查
make fix        # 自动修复
make test       # 运行测试
make test-unit  # 仅单元测试
```

## 技术栈

- Python 3.12+
- uv (包管理)
- hatchling (构建)
- Ruff (lint + format, line-length=100)
- mypy strict (类型检查)
- pytest + pytest-asyncio (测试)
- FastAPI (服务端 API)

## 规则来源

- 仓库贡献规范：`../../../CONTRIBUTING.md`
- Python lint、format、类型与测试配置：`pyproject.toml`
- 模块边界和术语：`CONTEXT.md`
- 对外接口契约：`docs/api/`

## 关键外部依赖

- agent-core (`openjiuwen` 包): ReActAgent, SkillManager
- agent_evolving (`openjiuwen` 包): SkillDocumentOptimizer, Trainer, SingleDimUpdater, Callbacks

## 关键约束速查

- `types.py` 和 `config.py` 是叶子模块
- `optimizer_runner.py` 是唯一编排入口
- 场景通过 SkillDocumentOptimizer 子类化实现定制，直接覆写管线阶段
- Callback 顺序: `SkillDocumentCallbacks` → `ProgressCallback`
- Frontmatter 优化开关（`EvolveConfig.preserve_frontmatter`，默认 `True`，env `EVO_PRESERVE_FRONTMATTER`）：
  - `True`（默认，已部署场景零配置）：写回冻结 frontmatter（`FrontmatterPreservingSkillDocumentOperator`）+ LLM 反思输入 strip frontmatter（`SkillDocumentOptimizer._llm_skill_view` 仅在 reflect/aggregate/select/slow_update/meta_skill 注入边界 strip；apply/snapshot/diff/remote 全程走全文）
  - `False`：frontmatter 全程参与——LLM 可见、可被 edit 改动并随回写同步（普通 `SkillDocumentOperator`）
  - 透传链：链 A（写回）`config.preserve_frontmatter` → `optimizer_runner._build_operators` → `build_skill_document_operator`；链 B（LLM strip）`config.preserve_frontmatter` → `ResolvedOptimizationConfig.optimizer_runtime_dependencies()` → registry `_filter_kwargs` → `SkillDocumentOptimizer.__init__` → `self._preserve_frontmatter` → `_llm_skill_view`
