---
name: product_recommend_skill
description: 根据用户风险偏好与资金情况推荐理财产品。
tools:
  - search_products
  - read_file
---

# Product Recommend Skill

## 触发条件

用户询问理财产品推荐、稳健型/进取型产品选择时使用。

## 执行步骤

1. 解析用户风险偏好（保守 / 稳健 / 进取）与可投资金额。
2. 调用 `search_products` 检索匹配产品列表。
3. 按收益、期限、风险等级排序后给出 2–3 款推荐并说明理由。

## 输出要求

- 使用简洁中文，列出产品名称、预期收益区间、适合人群。
- 结尾附风险提示：理财非存款，产品有风险。
