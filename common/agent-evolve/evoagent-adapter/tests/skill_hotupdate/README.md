# Skill 热更新测试

本目录存放 **EvoAgentAdapter Skill 热更新**特性的手工集成脚本、E2E 验证脚本及配套文档。

## 目录结构

```
tests/skill_hotupdate/
├── README.md
├── docs/
│   ├── Skill热更新测试用例.md
│   ├── Skill热更新测试用例执行报告.md
│   └── Skill热更新开发串讲文档.md
└── scripts/
    ├── config.py              # 环境变量 / CLI 参数
    ├── run_api_suite.py       # TC-01 ~ TC-12 集成套件
    └── run_e2e_experiment.py  # TC-13 ~ TC-14 E2E 实验
```

## 测试环境要求

### 集成 / E2E（本目录脚本）

| 项目 | 要求 |
| --- | --- |
| Python | 3.10+（仅标准库，无额外依赖） |
| EvoAgentAdapter | 已启动并可访问（默认 `http://127.0.0.1:8900`） |
| 业务 Agent | 已在 Adapter 配置中注册 `agent_name`，且配置 `agent_url` |
| Skills 目录 | Adapter 与业务 Agent **共享挂载**同一 `skills_dir` |
| 网络 | 执行机可访问 Adapter HTTP 端口；对话用例需业务 Agent 可达 |

推荐验证组合：Adapter + `edp_agent`（EDPAgent）+ 理财场景 Skill（如 `product_recommend_skill`）。

### EvoAgent 客户端单元测试（可选）

在 **EvoAgent** 工程中执行（见 `community/EvoAgent`）：

| 项目 | 要求 |
| --- | --- |
| Python | ≥ 3.12 |
| pytest | ≥ 8.0；`pytest-asyncio`、`pytest-httpx` |
| openjiuwen | 与 EvoAgent 源码匹配的版本（通常 editable 安装 `agent-core`） |
| EvoAgent | `pip install -e community/EvoAgent[dev]` |

## 快速执行

```bash
cd tests/skill_hotupdate/scripts

# 默认连接本机 Adapter
python run_api_suite.py
python run_e2e_experiment.py

# 指定远程或自定义 Agent / Skill
python run_api_suite.py --base-url http://<host>:8900 --agent-name edp_agent --skill-name product_recommend_skill
```

或使用环境变量：

```bash
export ADAPTER_URL=http://<host>:8900
export ADAPTER_AGENT_NAME=edp_agent
export ADAPTER_SKILL_NAME=product_recommend_skill
python run_api_suite.py
```

## 相关文档

- Adapter Skill 存储设计：`docs/skills-storage-design.md`
- Adapter 使用指导 Skill 章节：`docs/deployment-guide.md`（1.4 Skill 存储设计 / 4.1.7 Skill 管理）
- API 契约：`community/EvoAgent/docs/api/adapter-api-contract.md`

## 注意事项

1. E2E 脚本会在 Skill 正文中**临时注入**验证块，结束前会调用 `restore_skill` 还原。
2. 热更后验证对话须使用**新的** `conversation_id`（脚本已自动生成）。
3. 若对话响应 `answer` 为空，以 `GET .../traces/{conv_id}` 中 `GENERATION.output.content` 为准。
4. Windows 执行脚本时建议设置 `PYTHONIOENCODING=utf-8`，避免控制台编码问题。

## 最近验证记录

| 日期 | 范围 | 环境 | 结果 |
| --- | --- | --- | --- |
| 2026-06-18 | TC-01～14 集成/E2E | `124.71.234.237:8900` + `edp_agent` | **14/14 通过** |
| 2026-06-17 | EvoAgent 单元 28 pytest | 本地 agent-core + EvoAgent[dev] | **28/28 通过** |

详见 [`docs/Skill热更新测试用例执行报告.md`](docs/Skill热更新测试用例执行报告.md)。
