---
name: interact_finance_rec_skill
description: 多轮交互式理财咨询，逐步澄清用户需求后给出建议。
tools:
  - search_products
---

# Interact Finance Rec Skill

## 触发条件

用户需求模糊、需要追问澄清（如「帮我理财」「有什么好的」）时使用。

## 执行步骤

1. 若缺少关键信息，先追问：投资期限、风险承受能力、流动性要求。
2. 汇总用户回答，映射到内部标签（risk_level、horizon_months）。
3. 调用检索工具获取候选产品，以对话形式呈现推荐。

## 注意事项

- 每轮最多追问 2 个问题，避免打断用户体验。
- 确认用户意图后再给出最终推荐列表。
