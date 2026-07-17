---
name: faq_skill
description: 回答常见问题（营业时间、联系方式、办理流程）。
tools:
  - search_knowledge_base
---

# FAQ Skill

## 触发条件

用户询问营业时间、网点地址、业务办理流程等标准 FAQ。

## 执行步骤

1. 将用户问题向量化检索知识库。
2. 若命中高置信度条目，直接引用官方表述回答。
3. 未命中时提示转人工或给出相近话题链接。
