## Skill 热更新测试用例执行报告

### 1. 测试概述

| 项目 | 内容 |
| --- | --- |
| **测试对象** | EvoAgentAdapter Skill API + 业务 Agent 运行时热加载 + EvoAgent `AdapterClient` / Operator（单元） |
| **集成/E2E 脚本** | `tests/skill_hotupdate/scripts/run_api_suite.py`、`run_e2e_experiment.py` |
| **单元测试** | `community/EvoAgent/tests/unit/test_adapter_client.py`、`test_operator_factory.py` |
| **Adapter 地址** | `http://124.71.234.237:8900`（最近一次实测） |
| **a2a_service** | `http://124.71.234.237:18090/health` → healthy |
| **agent_name** | `edp_agent` |
| **skill_name** | `product_recommend_skill` |
| **执行日期** | 2026-06-18（集成/E2E）；2026-06-17（单元） |
| **执行环境** | Windows；Python 3.12 |

### 2. 测试执行结果

#### 2.1 测试统计

| 指标 | 集成/E2E（TC-01～14） | EvoAgent 单元（28 pytest） | 合计 |
| --- | --- | --- | --- |
| **通过** | 14 | 28 | **42** |
| **失败** | 0 | 0 | **0** |
| **跳过** | 0 | 0 | 0 |
| **警告** | — | 2 | 2 |
| **执行时间** | 约 47s（26s + 21s） | 5.93s | 约 53s |
| **通过率** | 100% | 100% | **100%** |

#### 2.2 集成/E2E 结果

| 类别 | 编号 | 用例 | 状态 |
| --- | --- | --- | --- |
| Adapter API | TC-01 | skill_list 返回目标 skill | ✅ 通过 |
| | TC-02 | skill_content 返回完整 Markdown | ✅ 通过 |
| | TC-03 | update_skill 写盘且读回一致 | ✅ 通过 |
| | TC-08 | 更新不存在 skill → SKILL_NOT_FOUND | ✅ 通过 |
| | TC-09 | 未知 agent_name → 404 | ✅ 通过 |
| | TC-10 | 非法 action → 400 | ✅ 通过 |
| | TC-11 | Adapter /health | ✅ 通过 |
| 热更+对话 | TC-04 | 热更后新会话对话成功 | ✅ 通过 |
| | TC-05 | traces 含 SKILL 记录 | ✅ 通过 |
| | TC-12 | 热更后 traces 可采集 | ✅ 通过 |
| 快照恢复 | TC-06 | restore 恢复到快照前内容 | ✅ 通过 |
| | TC-07 | restore 幂等 | ✅ 通过 |
| E2E | TC-13 | 注入标记，回答首行含热更标记 | ✅ 通过 |
| | TC-14 | restore 后与优化前一致 | ✅ 通过 |

#### 2.3 单元测试详细结果（28 项 pytest）

| 测试类别 | 用例编号 | pytest 用例 | 状态 |
| --- | --- | --- | --- |
| **AdapterClient 构造** | TC-18 | `TestConstruction::test_empty_agent_name_raises` | ✅ 通过 |
| | TC-18 | `TestConstruction::test_default_agent_name_raises` | ✅ 通过 |
| **对话 invoke（rollout 支撑）** | TC-15c | `TestInvoke::test_invoke_success` | ✅ 通过 |
| | TC-15c | `TestInvoke::test_invoke_with_extra_data` | ✅ 通过 |
| | TC-15c | `TestInvoke::test_invoke_adapter_error_404` | ✅ 通过 |
| | TC-15c | `TestInvoke::test_invoke_business_failure_via_success_field` | ✅ 通过 |
| | TC-15c | `TestInvoke::test_invoke_retry_on_502` | ✅ 通过 |
| | TC-15c | `TestInvoke::test_invoke_no_retry_on_400` | ✅ 通过 |
| **轨迹 get_traces** | TC-15c | `TestGetTraces::test_get_traces_success` | ✅ 通过 |
| | TC-15c | `TestGetTraces::test_get_traces_empty` | ✅ 通过 |
| **Skill 热更 update_skill** | TC-15 | `TestUpdateSkill::test_update_skill_success` | ✅ 通过 |
| | TC-15 | `TestUpdateSkill::test_update_skill_failure` | ✅ 通过 |
| | TC-15 | `TestUpdateSkill::test_update_skill_retry_on_502` | ✅ 通过 |
| | TC-15 | `TestUpdateSkill::test_update_skill_no_retry_on_400` | ✅ 通过 |
| | TC-15 | `TestUpdateSkill::test_update_skill_retry_exhausted` | ✅ 通过 |
| **Skill 读取** | TC-15b | `TestSkillOperations::test_skill_list_success` | ✅ 通过 |
| | TC-15b | `TestSkillOperations::test_skill_content_success` | ✅ 通过 |
| **生命周期** | — | `TestLifecycle::test_close` | ✅ 通过 |
| | — | `TestLifecycle::test_context_manager` | ✅ 通过 |
| **Operator 工厂** | TC-16 | `test_creates_operator` | ✅ 通过 |
| | TC-16 | `test_callback_triggers_update_skill` | ✅ 通过 |
| | TC-16 | `test_callback_via_set_parameter` | ✅ 通过 |
| | TC-18 | `test_callback_via_load_state` | ✅ 通过 |
| | TC-18 | `test_callback_is_sync` | ✅ 通过 |
| | TC-18 | `test_operator_name` | ✅ 通过 |
| **Frontmatter 保留** | TC-17 | `test_preserves_frontmatter_on_set_parameter` | ✅ 通过 |
| | TC-17 | `test_get_state_returns_full_document` | ✅ 通过 |
| | TC-17 | `test_load_state_ignores_frontmatter_edits` | ✅ 通过 |

---

### 3. 执行记录详情

#### 3.1 集成套件（12/12，2026-06-18）

```
命令:
  cd tests/skill_hotupdate/scripts
  python run_api_suite.py --base-url http://124.71.234.237:8900

结果:
  TC-01 skill_list: fund_planning_skill, interact_finance_rec_skill,
                    product_recommend_skill, product_select_skill
  TC-02 skill_content 长度 5714
  TC-03 update_skill success=True
  TC-04 对话 success=True
  TC-05 traces SKILL 记录数 1
  TC-06 restore 后长度 5714（与原始一致）
  TC-07 restore 幂等 success=True
  TC-08~11 错误码与 health 符合预期
  TC-12 traces calls=5

耗时: 约 26s
```

#### 3.2 E2E 实验（TC-13/14，2026-06-18）

```
命令:
  python run_e2e_experiment.py --base-url http://124.71.234.237:8900

结果:
  标记: 【SKILL热更-20260618-000913】
  conv: skill-hotfix-1781712553
  读回校验 PASS
  回答含标记、首行标记 PASS（answer 从 traces GENERATION 提取）
  traces SKILL 命中 PASS
  restore 后与原始 5714 字一致 PASS

耗时: 约 21s
```

#### 3.3 EvoAgent 单元测试（28/28，2026-06-17）

```
环境:
  Python 3.12.13, pytest 9.1.0, pytest-asyncio 1.4.0, pytest-httpx 0.36.2
  pip install -e <path-to-agent-core>
  pip install -e <path-to-community/EvoAgent>[dev]

命令:
  cd community/EvoAgent
  python -m pytest tests/unit/test_adapter_client.py \
                  tests/unit/test_operator_factory.py -v

结果: 28 passed, 2 warnings in 5.93s
```

> 说明：若仅安装 PyPI 版 `openjiuwen` 而未装本地 `agent-core`，会因缺少 `SKILL_CONTENT_TARGET` 导致 collection 阶段 `ImportError`。

---

### 4. 测试执行日志（节选）

#### 4.1 集成套件

```
======================================================================
Skill 热更新 API 测试套件 | http://124.71.234.237:8900 | agent=edp_agent
======================================================================
  [PASS] TC-01 ~ TC-12（详见 §3.1）
======================================================================
合计: 12/12 通过
```

#### 4.2 E2E 实验

```
实验汇总
  读回校验:         PASS
  traces read_file: PASS
  回答含标记:       PASS
  标记为首行:       PASS
  restore:          PASS
```

#### 4.3 单元测试（pytest 全量）

```
tests/unit/test_adapter_client.py::TestConstruction::test_empty_agent_name_raises PASSED
tests/unit/test_adapter_client.py::TestConstruction::test_default_agent_name_raises PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_success PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_with_extra_data PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_adapter_error_404 PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_business_failure_via_success_field PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_retry_on_502 PASSED
tests/unit/test_adapter_client.py::TestInvoke::test_invoke_no_retry_on_400 PASSED
tests/unit/test_adapter_client.py::TestGetTraces::test_get_traces_success PASSED
tests/unit/test_adapter_client.py::TestGetTraces::test_get_traces_empty PASSED
tests/unit/test_adapter_client.py::TestUpdateSkill::test_update_skill_success PASSED
tests/unit/test_adapter_client.py::TestUpdateSkill::test_update_skill_failure PASSED
tests/unit/test_adapter_client.py::TestUpdateSkill::test_update_skill_retry_on_502 PASSED
tests/unit/test_adapter_client.py::TestUpdateSkill::test_update_skill_no_retry_on_400 PASSED
tests/unit/test_adapter_client.py::TestUpdateSkill::test_update_skill_retry_exhausted PASSED
tests/unit/test_adapter_client.py::TestSkillOperations::test_skill_list_success PASSED
tests/unit/test_adapter_client.py::TestSkillOperations::test_skill_content_success PASSED
tests/unit/test_adapter_client.py::TestLifecycle::test_close PASSED
tests/unit/test_adapter_client.py::TestLifecycle::test_context_manager PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_creates_operator PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_callback_triggers_update_skill PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_callback_via_set_parameter PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_callback_via_load_state PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_callback_is_sync PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_operator_name PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_preserves_frontmatter_on_set_parameter PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_get_state_returns_full_document PASSED
tests/unit/test_operator_factory.py::TestBuildSkillDocumentOperator::test_load_state_ignores_frontmatter_edits PASSED

======================= 28 passed, 2 warnings in 5.93s =======================
```

---

### 5. 发现与说明

| 序号 | 类型 | 描述 | 影响 |
| --- | --- | --- | --- |
| 1 | 行为 | 对话响应 `answer` 常为空；E2E 从 traces `GENERATION.output.content` 取最终文本 | E2E 断言应优先 traces |
| 2 | 约束 | 热更后须使用新 `conversation_id` | EvoAgent 已用 `ConversationIdFactory` |
| 3 | 约束 | `update_skill` 仅改正文；frontmatter 运行时语义需重启业务 Agent | `FrontmatterPreservingSkillDocumentOperator` |
| 4 | 环境 | Windows 下 `conda run` 输出中文可能触发 GBK 编码错误；建议 `PYTHONIOENCODING=utf-8` 或直接调用虚拟环境 python | 见 §6 复现命令 |
| 5 | 稳定性 | 业务 Agent 不可达时对话用例可能 300s 超时；执行前确认 `18090/health` 与 Adapter 对话探活 | 集成套件 TC-04/12 |
| 6 | pytest 警告 | `PydanticDeprecatedSince20`（connector_pool / http_client） | 不影响热更测试结果 |

### 6. 结论

✅ **集成/E2E 与单元测试全部通过（42/42）**

Skill 热更新特性验证结论：

1. **Adapter 写盘**：`update_skill` 原子写入共享 `SKILL.md`，`skill_content` 可即时读回（TC-01～03）。
2. **业务 Agent 无重启生效**：新会话读取更新文档，用户回复体现热更内容（TC-13 E2E）。
3. **快照与恢复**：`restore_skill` 幂等还原（TC-06～07、TC-14）。
4. **EvoAgent 客户端**：`AdapterClient.update_skill` 契约、重试、同步 callback 链经 28 项单元测试覆盖（TC-15～18）。
5. **frontmatter 保护**：优化回写不破坏 YAML 头（TC-17 单元 + 设计约束）。

### 7. 测试覆盖范围

| 模块 | 测试覆盖 |
| --- | --- |
| `EvoAgentAdapter` `SkillStore` | ✅ update / read / restore / list（远程） |
| `EvoAgentAdapter` `routes.py` | ✅ `/api/v1/skills` 多 action（远程） |
| `EvoAgent` `AdapterClient` | ✅ 19 项 pytest（invoke / traces / skill CRUD / 重试） |
| `EvoAgent` `operator.py` | ✅ 9 项 pytest（callback / frontmatter / load_state） |
| 业务 Agent 运行时读 Skill | ✅ 对话 + traces（远程 E2E） |
| `evo_agent.optimizer_runner` 编排 | 📋 REF 用例（文档化，未全自动） |

### 8. 复现命令

```bash
cd tests/skill_hotupdate/scripts

# 集成 + E2E（将 base-url 换成目标 Adapter）
python run_api_suite.py --base-url http://<host>:8900 --agent-name edp_agent
python run_e2e_experiment.py --base-url http://<host>:8900

# EvoAgent 单元（在 EvoAgent 工程目录）
pip install -e path/to/agent-core
pip install -e path/to/community/EvoAgent[dev]
cd path/to/community/EvoAgent
python -m pytest tests/unit/test_adapter_client.py tests/unit/test_operator_factory.py -v
```

Windows 建议：

```powershell
$env:PYTHONIOENCODING='utf-8'
python run_api_suite.py --base-url http://<host>:8900
```
