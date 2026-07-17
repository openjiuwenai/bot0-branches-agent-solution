---
name: product_select_skill
description: 在用户已指定产品类型或名称时，帮助对比筛选具体产品。
tools:
  - search_products
  - compare_products
---

# Product Select Skill

## 触发条件

用户已明确产品类型（基金、理财、保险等）或给出候选产品名称需要对比。

## 执行步骤

1. 提取产品类型、筛选条件（收益、期限、起购金额）。
2. 检索并过滤候选集。
3. 使用 `compare_products` 生成对比维度表（收益、风险、流动性）。
4. 给出首选与备选各一款。
