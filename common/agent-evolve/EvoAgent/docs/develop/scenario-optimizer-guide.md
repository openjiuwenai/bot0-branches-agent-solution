# 基于 SkillDocumentOptimizer 开发场景化优化器

本文档面向场景开发者：你有一个 AI Agent 技能（SKILL.md）、一批测试用例（dataset）、一个远程 rollout 服务，想让技能文档自动优化。

你不需要修改 agent-core，只需要：
1. 建一个场景文件夹
2. 写一个 `scenario.yaml`
3. 写一个 `SkillDocumentOptimizer` 子类，覆写需要定制的管线阶段

---

## 目录

- [整体架构](#整体架构)
- [场景文件夹结构](#场景文件夹结构)
- [scenario.yaml 配置](#scenarioyaml-配置)
- [optimizer.py — 场景 Optimizer 子类](#optimizerpy--场景-optimizer-子类)
  - [可覆写的管线阶段](#可覆写的管线阶段)
  - [访问内部工具](#访问内部工具)
- [提示词覆盖](#提示词覆盖)
- [如何决定覆写哪些阶段](#如何决定覆写哪些阶段)
- [完整示例：从零建一个场景](#完整示例从零建一个场景)
- [配置参考（EvolveConfig）](#配置参考evolveconfig)
- [回调顺序（正确性约束）](#回调顺序正确性约束)
- [API 与服务端部署](#api-与服务端部署)
- [常见问题](#常见问题)

---

## 整体架构

```
用户/平台
    ↓
OptimizeRequest(scenario="my_scenario")
    ↓
ScenarioRegistry — 加载 scenarios/my_scenario/scenario.yaml
    ↓
动态 import optimizer_class → 实例化 SkillDocumentOptimizer 子类
    ↓
Trainer — epoch 循环驱动：
    _rollout → _format_single → _reflect → _aggregate → _select → skill update
    ↓
OptimizeReport
```

EvoAgent **不修改** agent-core 的任何类。场景差异完全通过继承 `SkillDocumentOptimizer` 并覆写管线阶段方法实现。

---

## 场景文件夹结构

在 `scenarios/` 下建一个以场景名命名的文件夹：

```
scenarios/my_scenario/
├── scenario.yaml           ← 必需：optimizer 类路径 + 超参数
├── optimizer.py            ← 必需（通常）：SkillDocumentOptimizer 子类
├── prompts/                ← 可选：覆盖默认 analyst 提示词
│   ├── analyst_error.md
│   └── analyst_success.md
└── skills/
    └── initial.md          ← 可选：初始 skill 文档模板
```

- 文件夹名即场景名（`OptimizeRequest.scenario` 字段的值）
- `scenario.yaml` 是唯一必需文件
- `optimizer.py` 包含你的 `SkillDocumentOptimizer` 子类
- `prompts/` 和 `skills/` 按需添加

---

## scenario.yaml 配置

```yaml
schema_version: "1.0"
description: "简短描述这个场景做什么"
optimizer_class: optimizer.MyScenarioOptimizer
hyperparams:
  batch_size: 4
  score_threshold: 0.5
```

### 字段说明

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `schema_version` | str | ✅ | 固定 `"1.0"` |
| `description` | str | ❌ | 场景描述 |
| `optimizer_class` | str | ✅ | 相对于场景文件夹的类路径 |
| `hyperparams` | dict | ❌ | 传给 SkillDocumentOptimizer 构造函数的超参数 |

### `optimizer_class` 路径规则

| 写法 | 含义 |
|------|------|
| `optimizer.MyOptimizer` | 相对路径，从场景文件夹加载 `optimizer.py` |
| `my_module.MyOptimizer` | 相对路径，从场景文件夹加载 `my_module.py` |
| `evo_agent.xxx.YyyOptimizer` | 全限定包路径，`importlib.import_module` 加载 |

### `hyperparams` 规则

`hyperparams` 里的参数直接传给 `SkillDocumentOptimizer` 的构造函数。
`optimizer_runner.py` 注入的运行时依赖（agent, evaluator, llm, model, train_cases 等）优先级高于 YAML 里的 `hyperparams`。
注册器会自动过滤掉构造函数不接受的多余参数。

---

## optimizer.py — 场景 Optimizer 子类

核心思路：继承 `SkillDocumentOptimizer`，覆写你需要定制的管线阶段方法。未覆写的方法自动使用基类默认实现。

### 最小骨架

```python
"""My scenario optimizer."""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.optimizer import SkillDocumentOptimizer


class MyOptimizer(SkillDocumentOptimizer):
    """覆写需要的阶段，其他用基类默认。"""
    pass
```

如果你不需要定制任何阶段，甚至可以不写 `optimizer.py`，让 `scenario.yaml` 指向一个空子类。

### 可覆写的管线阶段

| 方法 | 签名 | 用途 |
|------|------|------|
| `_rollout` | `async (cases, skill_content) → (evaluated_cases, trajectories)` | 自定义 rollout（如远程 agent 调用） |
| `_format_single` | `(trajectory, evaluated_case, case) → str` | Trajectory 清洗 + 格式化 |
| `_reflect` | `async (formatted_batch, skill_content, score_threshold, batch_data?) → list` | 自定义反思逻辑 |
| `_aggregate` | `(reflections) → aggregated` | 聚合多个反思结果 |
| `_select` | `(aggregated) → edits` | 选择最佳候选更新 |
| `_build_analyst_prompt` | `(template_name, skill_content, trajectories_text, step_buffer, meta_skill) → str` | 自定义 analyst prompt 构建 |

### 访问内部工具

子类可以直接访问 `SkillDocumentOptimizer` 的内部工具，这是子类化模式的核心优势：

| 属性 | 类型 | 用途 |
|------|------|------|
| `self._llm` | `Model` | LLM 调用（反思、聚合等） |
| `self._evaluator` | `BaseEvaluator` | 评估预测结果 |
| `self._agent` | `BaseAgent` | 目标 Agent |
| `self._scheduler` | `Scheduler` | 学习率 / edit budget 调度 |
| `self._format_batch()` | method | 批量格式化（内部 helper） |

### 示例：覆写 _format_single

```python
class FundAdvisorOptimizer(SkillDocumentOptimizer):
    """基金顾问场景 — 定制 trajectory 格式化。"""

    def _format_single(
        self,
        trajectory: Any,
        evaluated_case: Any,
        case: Any,
    ) -> str:
        """清洗 OTel 轨迹 → 渲染为 analyst 可读文本。"""
        # 1. 清洗：从 OTel spans 提取关键步骤
        cleaned = self._clean_otel_trace(trajectory)

        # 2. 格式化：渲染为文本
        parts = [
            f"## Case: {case.case_id}",
            f"**Input**: {case.inputs.get('question', '')}",
            f"**Expected**: {case.label.get('answer', '')}",
            f"**Score**: {evaluated_case.score:.2f}",
            "",
            "### Execution Steps",
            cleaned,
        ]
        return "\n".join(parts)

    def _clean_otel_trace(self, raw: Any) -> str:
        # 场景特定的 OTel trace 清洗逻辑
        ...
```

### 示例：覆写 _reflect

```python
class CustomerServiceOptimizer(SkillDocumentOptimizer):
    """客服场景 — 定制反思逻辑，需要额外上下文。"""

    async def _reflect(
        self,
        formatted_batch: str,
        skill_content: str,
        score_threshold: float,
        batch_data: list[Any] | None = None,
    ) -> list[Any]:
        """在反思时注入领域知识库上下文。"""
        # 访问 self._llm 做额外的 LLM 调用
        domain_context = await self._fetch_domain_knowledge(formatted_batch)

        # 增强 formatted_batch
        enhanced = f"{formatted_batch}\n\n## Domain Context\n{domain_context}"

        # 委托给基类默认反思
        return await super()._reflect(
            enhanced, skill_content, score_threshold, batch_data
        )
```

---

## 提示词覆盖

场景可以通过在 `prompts/` 目录下放置同名文件来覆盖默认的 analyst 提示词。

### 两级查找机制

`load_prompt(name, scenario_name)` 按以下顺序查找：

1. **场景覆盖**：`scenarios/<name>/prompts/<prompt>.md`
2. **agent-core fallback**：`openjiuwen.agent_evolving.optimizer.skill_document.prompts`

### 可覆盖的 prompt

| 文件名 | 用途 |
|--------|------|
| `analyst_error.md` | 分析失败轨迹时的 system prompt |
| `analyst_success.md` | 分析成功轨迹时的 system prompt |

### 提示词写作建议

- System prompt 定义 analyst 的角色和分析框架
- User prompt 由 `_build_analyst_prompt` 自动构建（包含 skill 内容、trajectory、edit budget 等）
- 如果默认 user prompt 结构不满足需求，覆写 `_build_analyst_prompt`

---

## 如何决定覆写哪些阶段

| 你的需求 | 覆写的方法 |
|---------|-----------|
| 远程 Agent 用非 HTTP 协议 | `_rollout` |
| 原始轨迹需要特殊清洗（OTel/自定义格式） | `_format_single` |
| 需要领域知识增强的反思 | `_reflect` |
| 自定义 prompt 就够了 | 不需要覆写，只放 `prompts/` 文件 |
| 完全默认行为 | 空子类 + `scenario.yaml` |

---

## 完整示例：从零建一个场景

### 步骤 1：创建文件夹

```bash
mkdir -p scenarios/fund_advisor/prompts scenarios/fund_advisor/skills
```

### 步骤 2：写 scenario.yaml

```yaml
schema_version: "1.0"
description: "基金顾问场景 — 定制轨迹格式化 + prompt 覆盖"
optimizer_class: optimizer.FundAdvisorOptimizer
hyperparams:
  batch_size: 4
  score_threshold: 0.5
  accumulation: 2
```

### 步骤 3：写 optimizer.py

```python
"""基金顾问场景 optimizer。"""

from __future__ import annotations

from typing import Any

from openjiuwen.agent_evolving.optimizer import SkillDocumentOptimizer


class FundAdvisorOptimizer(SkillDocumentOptimizer):
    """覆写 _format_single 处理基金领域的特殊轨迹格式。"""

    def _format_single(
        self,
        trajectory: Any,
        evaluated_case: Any,
        case: Any,
    ) -> str:
        # 清洗 + 格式化（示例）
        return super()._format_single(trajectory, evaluated_case, case)
```

### 步骤 4：添加 prompt 覆盖（可选）

```bash
cat > scenarios/fund_advisor/prompts/analyst_error.md << 'EOF'
You are a financial skill document analyst.

Analyze the failed trajectories below and suggest improvements
to the skill document. Focus on:
1. Missing financial disclaimers
2. Incorrect fund terminology
3. Incomplete risk warnings
EOF
```

### 步骤 5：添加初始 skill（可选）

```bash
cp your_skill.md scenarios/fund_advisor/skills/initial.md
```

### 步骤 6：运行

```bash
# CLI
python -m evo_agent.cli optimize \
  --scenario fund_advisor \
  --skill-name fund_advisor \
  --skill-path scenarios/fund_advisor/skills/initial.md \
  --dataset datasets/fund/dataset.yaml

# API
curl -X POST http://localhost:5050/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "scenario": "fund_advisor",
    "skill_name": "fund_advisor",
    "skill_path": "scenarios/fund_advisor/skills/initial.md",
    "dataset_manifest_path": "datasets/fund/dataset.yaml"
  }'
```

---

## 配置参考（EvolveConfig）

所有配置通过环境变量（`EVO_` 前缀）或 `.env` 文件设置：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `EVO_LLM_API_KEY` | `""` | LLM API key |
| `EVO_LLM_BASE_URL` | `https://api.openai.com/v1` | LLM API base URL |
| `EVO_OPTIMIZER_MODEL` | `gpt-4o` | optimizer 用的模型 |
| `EVO_TARGET_MODEL` | `gpt-4o` | 目标 Agent 用的模型 |
| `EVO_REMOTE_ENDPOINT` | `""` | 远程 Agent 地址 |
| `EVO_REMOTE_TIMEOUT` | `300.0` | 远程调用超时（秒） |
| `EVO_DEFAULT_EPOCHS` | `3` | 默认训练轮数 |
| `EVO_DEFAULT_BATCH_SIZE` | `4` | 默认 batch 大小 |
| `EVO_ACCUMULATION` | `2` | accumulation steps |
| `EVO_EDIT_BUDGET` | `10` | 每步最大编辑数 |
| `EVO_SCORE_THRESHOLD` | `0.5` | 成功/失败分界线 |
| `EVO_USE_SLOW_UPDATE` | `True` | 启用 slow update（全量重写） |
| `EVO_USE_META_SKILL` | `True` | 启用 meta skill（优化记忆） |

---

## 回调顺序（正确性约束）

Trainer 通过 `ComposedCallbacks` 组合多个回调。**顺序必须为**：

```
1. SkillDocumentCallbacks    ← 执行 run_epoch_end()（slow_update + meta_skill）
2. skill sync callback       ← 持久化/推送最终 skill
3. ProgressCallback          ← 采集进度到 Job
```

**为什么**：`SkillDocumentCallbacks.on_train_epoch_end()` 触发 `slow_update`，可能修改 operator 中的 skill 内容。如果 sync callback 在 `SkillDocumentCallbacks` 之前执行，会写出 `slow_update` 之前的旧 skill。

`build_callbacks()` 函数自动固化此顺序，场景开发者无需关心。

---

## API 与服务端部署

### 启动服务

```bash
uvicorn evo_agent.api.app:app --host 0.0.0.0 --port 5050
```

### 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/scenarios` | 列出所有场景（name + optimizer_class + hyperparams） |
| `POST` | `/optimize` | 提交优化任务，返回 job_id |
| `GET` | `/optimize/{job_id}` | 查询任务状态和进度 |

### 场景列表响应示例

```json
[
  {
    "name": "example",
    "optimizer_class": "optimizer.ExampleOptimizer",
    "hyperparams": {"batch_size": 4, "score_threshold": 0.5}
  },
  {
    "name": "fund_advisor",
    "optimizer_class": "optimizer.FundAdvisorOptimizer",
    "hyperparams": {"batch_size": 4, "accumulation": 2}
  }
]
```

---

## 常见问题

### Q: 我可以同时定制多个阶段吗？

可以。在一个子类里覆写任意多个方法即可。

### Q: hyperparams 和 EvolveConfig 里的参数冲突怎么办？

`hyperparams` 会传给 `SkillDocumentOptimizer` 构造函数。`EvolveConfig` 的值在 `optimizer_runner.py` 中映射为 dependencies 注入，优先级高于 `hyperparams`。

### Q: 不写 optimizer.py 行不行？

可以。如果 `scenario.yaml` 指向一个不覆写任何方法的空子类，所有阶段都用基类默认实现。你仍然可以通过 `prompts/` 覆盖提示词。

### Q: 场景间的 optimizer.py 同名会冲突吗？

不会。`ScenarioRegistry` 使用唯一模块名（`_evo_agent_scenario_<场景名>_<模块名>`）加载，不同场景的同名文件互不干扰。

### Q: 边界规则是什么？

- **始终做**：场景 optimizer 直接继承 `SkillDocumentOptimizer`
- **先问再做**：修改 `SkillDocumentOptimizer` 的 protected hook 签名（属于 agent-core 范畴）
- **永远不做**：重新引入策略协议（Protocol）或组合容器（ScenarioAdapter）；在 EvoAgent 层复制 ReflACT 编排逻辑
