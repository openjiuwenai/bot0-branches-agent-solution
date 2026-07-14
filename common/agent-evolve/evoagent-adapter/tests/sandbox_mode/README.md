# 沙箱模式测试

本目录存放 **EDPAgent sandbox + jiuwenbox + EvoAgentAdapter** 三容器沙箱模式的测试脚本与交付文档。

覆盖能力：轨迹采集、调用代理、Skill 热更新。

## 文档

| 文档 | 说明 |
| --- | --- |
| [`沙箱模式开发串讲文档.md`](docs/沙箱模式开发串讲文档.md) | Adapter 方案、配置、测试；**§7 jiuwenbox 进程关系** |
| [`jiuwenbox运行原理与隔离架构.html`](docs/jiuwenbox运行原理与隔离架构.html) | jiuwenbox 图示版（进程/IPC/隔离/时序图） |
| [`沙箱模式测试用例.md`](docs/沙箱模式测试用例.md) | 用例分级与检查点 |
| [`沙箱模式测试用例执行报告.md`](docs/沙箱模式测试用例执行报告.md) | 最近执行结果 |

## 测试层级（业界口径）

| 层级 | 含义 | 本目录入口 |
| --- | --- | --- |
| 单元 | mock 测模块逻辑 | `run_unit_suite.py` |
| 冒烟 | 服务可达 | `run_api_suite.py`（TC-01～03） |
| 集成 | 组件接口/FS/契约 | `run_api_suite.py`（TC-04～07、10～14）；E2E 脚本中的 TC-17 |
| 系统 | 含 LLM 的代理/轨迹切片 | `run_api_suite.py`（TC-08～09） |
| E2E | 热更前后对话业务路径 | `run_e2e_dialogue.py`（场景 TC-E2E） |

## 目录结构

```
tests/sandbox_mode/
├── README.md
├── docs/
│   ├── 沙箱模式开发串讲文档.md
│   ├── jiuwenbox运行原理与隔离架构.html
│   ├── 沙箱模式测试用例.md
│   └── 沙箱模式测试用例执行报告.md
├── scripts/
│   ├── config.py
│   ├── run_api_suite.py       # 冒烟 + 集成 + 系统（TC-01～14）
│   ├── run_e2e_dialogue.py    # E2E 场景（步骤含集成 TC-17）
│   └── run_unit_suite.py      # 单元
└── reports/
```

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Python | 脚本 3.10+；单元建议 Adapter `.venv`（≥3.12） |
| jiuwenbox | 默认 `http://127.0.0.1:8321` |
| EDPAgent | sandbox；健康检查默认 `:18001` |
| EvoAgentAdapter | `skill_backend: jiuwenbox`；默认 `:18900` |
| 模型 | 系统/E2E 对话用例需可用 LLM |

实验栈：`deployment/sandbox-experiment/start_stack.ps1`。

## 快速执行

```bash
cd tests/sandbox_mode/scripts
python run_unit_suite.py
python run_api_suite.py --adapter-url http://127.0.0.1:18900
python run_e2e_dialogue.py --adapter-url http://127.0.0.1:18900
```

环境变量：`ADAPTER_URL` / `EDP_URL` / `JIUWENBOX_URL` / `ADAPTER_AGENT_NAME` / `ADAPTER_SKILL_NAME`。

## 最近验证记录

| 日期 | 范围 | 结果 |
| --- | --- | --- |
| 2026-07-13 | 单元 13 | **13/13** |
| 2026-07-13 | 冒烟+集成+系统（api_suite） | **14/14 检查点** |
| 2026-07-13 | E2E 场景（含集成步骤） | **门禁 3/3；步骤 5/5** |

详见 [`docs/沙箱模式测试用例执行报告.md`](docs/沙箱模式测试用例执行报告.md)。
