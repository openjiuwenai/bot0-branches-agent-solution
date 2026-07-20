## Skill 热更新测试用例（EvoAgentAdapter + 业务 Agent 范围）

> **范围说明**：本章覆盖 EvoAgent 在 Skill 优化过程中，通过 EvoAgentAdapter 的 `POST /api/v1/skills` 接口对业务 Agent 进行 Skill 文档热更新，以及业务 Agent（如 EDPAgent）在**不重启**前提下通过对话中 `read_file` 读取更新后 `SKILL.md` 的端到端行为。
>
> **测试资产位置**：本仓库 `tests/skill_hotupdate/`（脚本 + 文档）。
>
> **不在本章范围**：EvoAgent 完整优化 Pipeline（LLM 反思/门控）、evoagent-studio 前端联调、frontmatter 运行时重新注册（需重启业务 Agent 进程）。

### 0. 环境与依赖

#### 0.1 集成 / E2E 脚本环境

| 项目 | 要求 |
| --- | --- |
| Python | 3.10+ |
| 依赖 | 无（脚本仅使用标准库） |
| EvoAgentAdapter | 运行中；默认 `http://127.0.0.1:8900` |
| 业务 Agent | 已在 Adapter `agents` 中配置，且 `agent_url` 可达 |
| Skills | Adapter 与业务 Agent 共享 `skills_dir` |
| 配置方式 | 环境变量 `ADAPTER_URL` / `ADAPTER_AGENT_NAME` / `ADAPTER_SKILL_NAME`，或脚本 `--base-url` 等参数 |

#### 0.2 EvoAgent 客户端单元测试环境（可选）

| 项目 | 要求 |
| --- | --- |
| Python | ≥ 3.12 |
| pytest | ≥ 8.0；`pytest-asyncio`、`pytest-httpx` |
| openjiuwen | 与 EvoAgent 源码版本一致（editable 安装 `agent-core`） |
| EvoAgent | editable 安装 `community/EvoAgent[dev]` |
| 测试文件 | `community/EvoAgent/tests/unit/test_adapter_client.py`、`test_operator_factory.py` |

> **路径约定**：集成/E2E 脚本路径相对于 **EvoAgentAdapter 仓库根**；单元测试路径相对于 **agent-store 根**（`community/EvoAgent` 与 `EvoAgentAdapter` 为同级目录）。

### 1. 测试用例总览

| 编号 | 测试层级 | 测试用例名称 | 优先级 | 自动化脚本 |
| --- | --- | --- | --- | --- |
| TC-01 | 集成 | `skill_list` 返回目标 Agent 已部署 Skill 列表 | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-02 | 集成 | `skill_content` 返回完整 Markdown（含 frontmatter） | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-03 | 集成 | `update_skill` 写盘且 `skill_content` 读回一致 | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-04 | 集成 | 热更后**新 conversation_id** 发起对话成功 | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-05 | 集成 | 对话 traces 含 `SKILL` 类型记录且命中目标 Skill | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-06 | 集成 | `restore_skill` 恢复到首次 `update_skill` 前快照 | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-07 | 集成 | `restore_skill` 幂等（快照保留） | P1 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-08 | 集成 | 更新不存在 Skill 返回 `SKILL_NOT_FOUND` | P0 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-09 | 集成 | 未知 `agent_name` 返回 HTTP 404 | P1 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-10 | 集成 | 非法 `action` 返回 HTTP 400 | P1 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-11 | 集成 | Adapter `/health` 可用 | P1 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-12 | 集成 | 热更后 traces 可采集（`calls` 非空） | P1 | `tests/skill_hotupdate/scripts/run_api_suite.py` |
| TC-13 | E2E | frontmatter 后注入验证规约，回答首行含热更标记 | P0 | `tests/skill_hotupdate/scripts/run_e2e_experiment.py` |
| TC-14 | E2E | `restore` 后 Skill 正文与优化前完全一致 | P0 | `tests/skill_hotupdate/scripts/run_e2e_experiment.py` |
| TC-15 | 单元 | `AdapterClient.update_skill` 请求体与重试（5 项） | P0 | `community/EvoAgent/tests/unit/test_adapter_client.py` |
| TC-15b | 单元 | `skill_list` / `skill_content`（2 项） | P0 | `community/EvoAgent/tests/unit/test_adapter_client.py` |
| TC-15c | 单元 | `invoke` / `get_traces` 支撑 rollout（9 项） | P1 | `community/EvoAgent/tests/unit/test_adapter_client.py` |
| TC-16 | 单元 | Operator 回调触发 `update_skill`（3 项） | P0 | `community/EvoAgent/tests/unit/test_operator_factory.py` |
| TC-17 | 单元 | 保留 YAML frontmatter（3 项） | P0 | `community/EvoAgent/tests/unit/test_operator_factory.py` |
| TC-18 | 单元 | `load_state` / 同步 callback（4 项） | P1 | `community/EvoAgent/tests/unit/test_operator_factory.py` |
| TC-19 | 契约 | 首次 `update_skill` 创建 `.meta/*.snapshot` | P0 | —（运维/手工，见 §4） |
| TC-20 | 约束 | 同 `conversation_id` 复用可能读到旧 Skill | P1 | —（设计约束，见 §3） |

---

### 2. Adapter Skill API 测试

#### TC-01：`skill_list` 返回已部署 Skill 列表

| 项目 | 内容 |
| --- | --- |
| **前置条件** | Adapter 已启动；目标 `agent_name` 已在 `agents` 配置中注册 |
| **测试步骤** | 1. `POST /api/v1/skills`，body：`{"agent_name":"<agent>","action":"skill_list"}`<br>2. 检查 HTTP 200<br>3. 检查 `skills` 数组含待测 `skill_name` |
| **预期结果** | 返回该 Agent 下所有含 `SKILL.md` 的子目录名（如 `edp_agent` 理财场景含 4 个 Skill） |

#### TC-02：`skill_content` 返回完整 Markdown

| 项目 | 内容 |
| --- | --- |
| **前置条件** | `{skill_name}/SKILL.md` 存在于共享 skills 目录 |
| **测试步骤** | 1. `POST /api/v1/skills`，`action=skill_content`，`skill_name=<skill_name>`<br>2. 验证 `content` 非空且以 `---` 开头（YAML frontmatter） |
| **预期结果** | 返回完整 Markdown 文档，长度 > 100 字符 |

#### TC-03：`update_skill` 写盘且读回一致

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 已通过 TC-02 获取原始 `content` |
| **测试步骤** | 1. 在正文中插入唯一标记 `<!-- HOTUPDATE_TC03_* -->`<br>2. `POST update_skill` 推送全文<br>3. 再次 `skill_content` 读回<br>4. 验证标记存在 |
| **预期结果** | `success=true`；读回内容与推送内容一致 |

#### TC-08：更新不存在 Skill 返回错误

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 无 |
| **测试步骤** | 1. `update_skill`，`skill_name=nonexistent_skill_xyz`<br>2. 检查 HTTP 状态与错误体 |
| **预期结果** | HTTP 404；`error.code=SKILL_NOT_FOUND` |

#### TC-09：未知 `agent_name` 返回 404

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 无 |
| **测试步骤** | 1. `skill_list`，`agent_name=no_such_agent` |
| **预期结果** | HTTP 404 |

#### TC-10：非法 `action` 返回 400

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 无 |
| **测试步骤** | 1. `POST /api/v1/skills`，`action=invalid_action` |
| **预期结果** | HTTP 400 |

#### TC-11：Adapter `/health` 可用

| 项目 | 内容 |
| --- | --- |
| **前置条件** | Adapter 已启动 |
| **测试步骤** | 1. `GET /health` |
| **预期结果** | HTTP 200；`status` 为 `ok` |

---

### 3. 热更新 + 业务对话联动

#### TC-04：热更后新会话对话成功

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-03 已完成热更 |
| **测试步骤** | 1. 生成新 `conversation_id`（格式建议 `{prefix}-{timestamp}`）<br>2. `POST /api/v1/agents/{agent_name}/conversations/{conv_id}`，body：`{"query":"推荐一款低风险理财产品"}`<br>3. 等待 SSE 消费完成 |
| **预期结果** | HTTP 200；`success=true` |

#### TC-05：对话 traces 含 SKILL 记录

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-04 对话已完成 |
| **测试步骤** | 1. `GET /api/v1/agents/{agent_name}/traces/{conv_id}`<br>2. 筛选 `type=="SKILL"` 的记录<br>3. 验证 `input` 中含目标 `skill_name` |
| **预期结果** | 至少 1 条 SKILL 记录；命中目标 Skill |

#### TC-12：热更后 traces 可采集

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 已完成一次热更 + 对话 |
| **测试步骤** | 1. 查询 traces<br>2. 验证 `calls` 数组非空 |
| **预期结果** | `calls.length >= 1`（含 GENERATION / SKILL 等） |

#### TC-20：同 conversation_id 复用约束（设计约束）

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 了解业务 Agent Redis checkpoint 机制 |
| **测试步骤** | 1. 在 conv_A 完成一次对话（读旧 Skill）<br>2. 热更 Skill<br>3. 复用 conv_A 再次对话 |
| **预期结果** | **可能仍使用 checkpoint 中的旧 `read_file` 结果**；EvoAgent 优化链路应使用 `ConversationIdFactory` 生成新 conv_id |
| **备注** | 本用例用于文档化约束，不作为自动化门禁 |

---

### 4. 快照与恢复

#### TC-06：`restore_skill` 恢复到快照前内容

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 已对某 Skill 执行过至少一次 `update_skill`（已创建快照） |
| **测试步骤** | 1. 记录优化前 `skill_content` 原文<br>2. `update_skill` 修改正文<br>3. `restore_skill`，`skill_names=["<skill_name>"]`<br>4. 再次 `skill_content` |
| **预期结果** | `restored[].success=true`；读回内容与步骤 1 完全一致 |

#### TC-07：`restore_skill` 幂等

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-06 已 restore 一次 |
| **测试步骤** | 1. 再次调用 `restore_skill`<br>2. 读回 `skill_content` |
| **预期结果** | 第二次仍 `success=true`；内容与首次 restore 后相同（快照不销毁） |

#### TC-19：首次 `update_skill` 创建快照文件

| 项目 | 内容 |
| --- | --- |
| **前置条件** | 可访问 Adapter 容器/主机 skills 目录（运维验证） |
| **测试步骤** | 1. 删除 `.meta/<skill_name>.snapshot`（若存在）<br>2. 执行 `update_skill`<br>3. 检查 `.meta/<skill_name>.snapshot` 是否生成 |
| **预期结果** | 快照文件存在；内容为**首次 update 前**的 SKILL.md 全文 |
| **备注** | 同一 Skill 后续多次 update **不覆盖**快照；路径 `{skills_dir}/.meta/{skill_name}.snapshot` |

---

### 5. E2E 验证（TC-13 / TC-14）

执行 `tests/skill_hotupdate/scripts/run_e2e_experiment.py`：

#### TC-13：注入验证规约，回答首行含热更标记

| 项目 | 内容 |
| --- | --- |
| **前置条件** | Skill 处于干净状态（可先 `restore_skill`） |
| **测试步骤** | 1. `skill_content` 取原文<br>2. 在 **frontmatter 之后**注入「最高优先级」验证块，要求用户可见回复第 1 行为 `【SKILL热更-{时间戳}】`<br>3. `update_skill` 推送<br>4. **新 conv_id** 发起理财推荐对话<br>5. 从 `answer` 或 traces `GENERATION.output.content` 取最终回复 |
| **预期结果** | 回复首行（去前导空行后）为热更标记；后续仍为正常推荐表格/话术 |

#### TC-14：`restore` 后 Skill 与优化前一致

| 项目 | 内容 |
| --- | --- |
| **前置条件** | TC-13 已完成 |
| **测试步骤** | 1. `restore_skill`<br>2. `skill_content` 与步骤 1 原始内容逐字比较 |
| **预期结果** | 完全一致；验证块已清除 |

---

### 6. EvoAgent 客户端单元测试

> **测试文件**：`community/EvoAgent/tests/unit/test_adapter_client.py`（19 项）、`test_operator_factory.py`（9 项），合计 **28 项 pytest**。

#### 6.1 环境准备

```bash
# 在 agent-store 根目录或各 community 子工程所在环境中执行
pip install -e path/to/agent-core
pip install -e path/to/community/EvoAgent[dev]

cd path/to/community/EvoAgent
python -m pytest tests/unit/test_adapter_client.py tests/unit/test_operator_factory.py -v
```

> 若 `openjiuwen` 版本与 EvoAgent 源码不一致，可能出现 `SKILL_CONTENT_TARGET` 等符号导入失败，需对齐 `agent-core` 版本。

#### 6.2 单元测试与用例映射

| 用例编号 | pytest 类 / 方法 | 验证点 |
| --- | --- | --- |
| **TC-15** | `TestUpdateSkill::test_update_skill_success` | body 含 `action=update_skill`、`agent_name`、`skill_content` |
| | `TestUpdateSkill::test_update_skill_failure` | `success=false` 抛 `AdapterError` |
| | `TestUpdateSkill::test_update_skill_retry_on_502` | 502 自动重试 |
| | `TestUpdateSkill::test_update_skill_no_retry_on_400` | 400 不重试 |
| | `TestUpdateSkill::test_update_skill_retry_exhausted` | 重试耗尽后抛错 |
| **TC-15b** | `TestSkillOperations::test_skill_list_success` | `action=skill_list` 解析 `skills` 数组 |
| | `TestSkillOperations::test_skill_content_success` | `action=skill_content` 返回 `content` 字符串 |
| **TC-15c** | `TestInvoke::*`（7 项） | 对话 invoke、extra_data、404/业务失败、502 重试 |
| | `TestGetTraces::*`（2 项） | 轨迹拉取成功 / 空响应 |
| **TC-16** | `test_callback_triggers_update_skill` | 直接调 callback → `update_skill` |
| | `test_callback_via_set_parameter` | `set_parameter` 端到端回写 |
| | `test_creates_operator` | operator 类型与 `skill_name` 正确 |
| **TC-17** | `test_preserves_frontmatter_on_set_parameter` | `set_parameter` 后回写含原 frontmatter |
| | `test_get_state_returns_full_document` | `get_state` 返回拼装后全文 |
| | `test_load_state_ignores_frontmatter_edits` | `load_state` 剥离对 frontmatter 的误改 |
| **TC-18** | `test_callback_via_load_state` | 门控回滚路径触发 `update_skill` |
| | `test_callback_is_sync` | callback 无 async 返回值 |
| | `test_operator_name` | `operator_id` 格式 `skill_document_{name}` |
| | `TestConstruction::*`（2 项） | 空 `agent_name` 构造校验 |

#### TC-15：`AdapterClient.update_skill` 请求体与重试

| 项目 | 内容 |
| --- | --- |
| **测试步骤** | 1. Mock HTTP transport<br>2. 调用 `update_skill(skill_name, skill_content)`<br>3. 断言 body 含 `action=update_skill`、`agent_name`<br>4. 502/503 触发重试；400 不重试 |
| **预期结果** | 与 `adapter-api-contract.md` §1.3 一致 |
| **pytest** | `TestUpdateSkill` 共 5 项 |

#### TC-16：`build_skill_document_operator` 回调触发 `update_skill`

| 项目 | 内容 |
| --- | --- |
| **测试步骤** | 1. Mock `AdapterClient`<br>2. 构建 operator<br>3. 调用 `on_parameter_updated("skill_content", "# New")` 或 `set_parameter` |
| **预期结果** | `mock_client.update_skill` 被调用一次，参数正确 |
| **pytest** | `test_callback_triggers_update_skill`、`test_callback_via_set_parameter` |

#### TC-17：优化时保留 YAML frontmatter

| 项目 | 内容 |
| --- | --- |
| **前置条件** | `initial_content` 含 `---\nname: demo\n---\n\n# body` |
| **测试步骤** | 1. `set_parameter("skill_content", "# Title\nnew body")`<br>2. 检查 `get_state` / `update_skill` 入参 |
| **预期结果** | 回写 `skill_content` 仍含原始 frontmatter |
| **pytest** | `test_preserves_frontmatter_on_set_parameter` 等 3 项 |

#### TC-18：`load_state` 触发回写

| 项目 | 内容 |
| --- | --- |
| **测试步骤** | 1. `load_state({"skill_content": "# Rolled Back"})` |
| **预期结果** | 触发 `update_skill`，用于门控候选回滚/提交场景 |
| **pytest** | `test_callback_via_load_state` |

---

### 7. EvoAgent 优化链路触发点（联调参考）

| 编号 | 场景 | 触发路径 | 预期 Adapter 调用 |
| --- | --- | --- | --- |
| REF-01 | 优化任务启动 | `run_optimization` → `restore_skill` | `restore_skill` |
| REF-02 | 构建 Operator | `skill_content` → `build_skill_document_operator` | `skill_content` |
| REF-03 | 每 step 应用 patch | `SkillDocumentOptimizer._sync_skill_to_operator_by_id` → `set_parameter` | `update_skill` |
| REF-04 | epoch 结束 slow_update | `_run_slow_update` → `_sync_skill_to_operator_by_id` | `update_skill` |
| REF-05 | 验证门控切换候选 | `apply_updates` / `load_state` | `update_skill` |
| REF-06 | 验证 rollout | `RemoteAgent.invoke` + `get_traces` | 对话 + 轨迹 API |

---

### 8. 脚本执行示例

```bash
cd tests/skill_hotupdate/scripts

python run_api_suite.py \
  --base-url http://127.0.0.1:8900 \
  --agent-name edp_agent \
  --skill-name product_recommend_skill

python run_e2e_experiment.py --base-url http://127.0.0.1:8900
```
