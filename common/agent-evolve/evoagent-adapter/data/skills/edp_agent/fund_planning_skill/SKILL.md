---
name: fund_planning_skill
description: 基金定投与资产配置规划。
tools:
  - search_funds
  - calc_allocation
---

# Fund Planning Skill

## 触发条件

用户咨询基金定投、资产配置、股债比例时使用。

## 执行步骤

1. 收集投资目标、每月可投金额、投资年限。
2. 根据年龄与风险偏好建议股债比例。
3. 调用 `calc_allocation` 计算各资产类别金额。
4. 推荐 2–4 只基金构成组合，并说明再平衡建议。

## 示例话术

「建议您采用 60% 债券型 + 40% 权益型配置，每月定投 3000 元……」
