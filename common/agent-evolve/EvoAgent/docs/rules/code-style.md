# 代码风格

## 命名约定

| 类型 | 风格 | 示例 |
|------|------|------|
| 类名 | PascalCase | `ScenarioRegistry`, `AdapterClient` |
| 函数/方法 | snake_case | `run_optimization()`, `build_optimizer()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_RETRIES` |
| 私有属性 | 前缀下划线 | `self._http`, `self._client` |
| Protocol | PascalCase | `ResourceResolver`, `Callbacks` |

## 文件组织

- 一个公共类/函数一个文件（小模块除外）
- `__init__.py` 只做导出，不放逻辑
- 测试文件与源文件对应：`src/evo_agent/config.py` → `tests/unit/test_config.py`

## Import 顺序

```python
# 1. 标准库
from __future__ import annotations
from pathlib import Path

# 2. 第三方
import httpx
from pydantic_settings import BaseSettings

# 3. 外部项目包
from openjiuwen.agent_evolving.optimizer import SkillDocumentOptimizer

# 4. 本项目
from evo_agent.config import EvolveConfig
```

各组之间空一行。

## 数据类

- 数据类使用 `@dataclass(frozen=True)`
- 可变字段用 `tuple` 代替 `list`
- 配置类用 `pydantic-settings`

## 异步

- HTTP 通信使用 `httpx.AsyncClient`
- 测试使用 `pytest-asyncio`，`asyncio_mode = "auto"`

## 并发约定（ADR-0006）

所有 LLM 调用受单一 `self._semaphore`（`parallelism`）封顶。`optimizer/concurrency.py` 提供两种 sanctioned 模式，禁止引入第三种：

1. **`gather_with_semaphore(semaphore, factories)`** — 用于协程自身**不** acquire semaphore 的并发（如 `_build_operators` 拉 skill 内容）。factory 在 semaphore 槽内惰性构造，绝不超过并发上限。
2. **裸 `asyncio.gather`** — 仅当每个被 gather 的协程**内部已** acquire semaphore（如经 `invoke_text_with_retry`）。外层 gather 不得再次 acquire，否则双重 acquire 死锁。每个此类调用点须在 docstring 记录"内部 semaphore"不变量。

跨 operator 的 reflect / aggregate / select（C2/C3/C4）走模式 2；`_build_operators` 的 skill 拉取（C5）走模式 1。

## 运行时配置合并

超参来源统一走 `OptimizationConfigResolver`（`runtime_config.py`），优先级 **request 字段 > scenario preset > env 默认值**。runner 与场景 optimizer 不得自行从多处拼装超参。reserved key（`agent`/`evaluator`/`llm`/`operators` 等）由 runner 注入，不进 `extra_hyperparams`。

## 格式化

- Ruff 统一管理（`line-length=100`）
- mypy strict 模式
