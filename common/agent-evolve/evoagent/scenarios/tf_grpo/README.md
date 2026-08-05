# tf_grpo 场景

**TF-GRPO**（Training-Free GRPO），仅优化 `SKILL.md`，用现有 `train_cases` + evaluator 打分。

## 算法节奏

每个 Trainer epoch = 一个 TF-GRPO epoch：

1. 从 `train_cases` 无放回采样 `cases_per_variant` 条（同 epoch 内 G 变体共用）
2. LLM 基于 current_best + ExperienceLibrary 生成 `group_size` 个 SKILL.md 变体
3. 每个变体：`update_skill` 热更 → Adapter 对话 rollout → evaluator 均分
4. 组内提 semantic advantage，更新经验库（Add/Delete/Modify/Keep）
5. 保留最高分变体，交给 Validation Gate

## 运行（服务化交付 · 主路径）

启动 EvoAgent API 后（须配置 `EVO_ADAPTER_URL` / LLM / `EVO_ALLOWED_DATA_ROOTS`）：

```bash
# 列表应含 tf_grpo
curl http://127.0.0.1:8000/scenarios

# 提交优化任务
curl -X POST http://127.0.0.1:8000/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "task_name": "tfgrpo-demo",
    "agent_name": "edp_agent",
    "optimizer_type": "skill",
    "skills": ["audit-business"],
    "dataset_path": "<绝对路径>/workspace/datasets/audit_min2/cases.json",
    "optimizer_template": {
      "name": "tf_grpo",
      "scenario": "tf_grpo",
      "hyperparams": {
        "num_epochs": 1,
        "group_size": 1,
        "cases_per_variant": 1,
        "num_parallel": 1
      },
      "train_split": 0.5,
      "val_split": 0.5
    },
    "evaluator_template": {
      "name": "default",
      "scenario": "audit-business",
      "prompt": "Compare predicted responsibility with expected; score 1 if match else 0."
    }
  }'

# 查询 / SSE / 取消
# GET  /optimize/{job_id}
# GET  /optimize/{job_id}/stream
# POST /optimize/{job_id}/cancel
```

运维与 Docker：`deployment/operations-guide.md`。测试交付：`docs/tf_grpo/TF-GRPO测试用例.md`。

## 运行（CLI · 调试辅路径）

```bash
# OptimizeRequest.scenario = tf_grpo；可用 dataset.yaml + metric evaluator
python skills/optimize_skill/scripts/run_optimize.py \
  --scenario tf_grpo \
  --dataset-manifest workspace/datasets/audit_business_balanced30/dataset.yaml \
  --agent-name edp_agent \
  --adapter-url http://127.0.0.1:18900
```

关键超参见 `scenario.yaml`：`group_size`、`cases_per_variant`、`variant_temperature`。

## 注意

- 热更后每个 case 必须使用新的 `conversation_id`（由 ConversationIdFactory 保证）
- 可选在 rollout `extra_data` 中传 `temperature`；Adapter 会写入下游 `custom_data.inputs`
- API 使用 `dataset_path`（默认 LLM evaluator）；CLI manifest 可配 `exact_match` 等 metric
- train/val 划分后两侧至少各 1 条 case（物理最小 2 条）
- 不做自动任务生成、不做 Skill 脚本代码优化
