# tf_grpo 场景

**TF-GRPO**（Training-Free GRPO），仅优化 `SKILL.md`，用现有 `train_cases` + evaluator 打分。

## 算法节奏

每个 Trainer epoch = 一个 TF-GRPO epoch：

1. 从 `train_cases` 无放回采样 `cases_per_variant` 条（同 epoch 内 G 变体共用）
2. LLM 基于 current_best + ExperienceLibrary 生成 `group_size` 个 SKILL.md 变体
3. 每个变体：`update_skill` 热更 → Adapter 对话 rollout → evaluator 均分
4. 组内提 semantic advantage，更新经验库（Add/Delete/Modify/Keep）
5. 保留最高分变体，交给 Validation Gate

## 运行

```bash
# 将 OptimizeRequest.scenario 设为 tf_grpo
# 其余与 edp_agent 相同：EVO_ADAPTER_URL、dataset、evaluator 等
```

关键超参见 `scenario.yaml`：`group_size`、`cases_per_variant`、`variant_temperature`。

## 注意

- 热更后每个 case 必须使用新的 `conversation_id`（由 ConversationIdFactory 保证）
- 可选在 rollout `extra_data` 中传 `temperature`；Adapter 会写入下游 `custom_data.inputs`
- 不做自动任务生成、不做 Skill 脚本代码优化
