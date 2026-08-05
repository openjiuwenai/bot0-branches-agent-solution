# SKILL.md — traffic-forecast-qa Skill 定义文件

---
name: traffic-forecast-qa
description: 流量预测智能问数 Skill，允许用户以自然语言提问，结合 db-connector 查询历史流量数据并完成流量预测与趋势分析。
version: 0.1.0
author: openjiuwen
tags: [traffic, forecast, qa, highway]
requires:
  tools:
    - db-connector
---

## 触发条件

当用户提问涉及以下意图时触发：

- 流量预测（"预测...流量"、"明天...多少车"）
- 流量趋势分析（"下周流量趋势"、"...变化"）
- 同比/环比对比（"对比去年同期"、"比上周..."）
- 异常预警（"流量异常"、"偏离..."）

## 工作流

1. **意图识别**：判断是否为流量预测/分析意图
2. **参数抽取**：提取目标对象（站点/路段）、时间范围、预测窗口、粒度
3. **参数校验 + 多轮澄清**：缺失/歧义参数主动追问
4. **SQL 生成**：生成参数化模板交由 db-connector 执行
5. **数据获取**：调用 db-connector.query() 获取历史数据
6. **预测计算**：L1 移动平均/同比环比 + L2 指数平滑/Holt-Winters
7. **结果组织**：点估计 + 置信区间 + 趋势描述 + 可视化建议

## 约束

- 仅调用 db-connector 的 `query()` 方法，不执行写操作
- 要求 db-connector 以 `readonly` 模式运行
- SQL 必须参数化，标识符取自配置白名单
- 预测粒度：1 小时
- 支持多轮澄清对话
