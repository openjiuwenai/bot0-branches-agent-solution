---
name: corporate_credit_123_sub_skill
description: >
  子Agent专用Skill：单企业信贷123分析（步骤1串行 + 步骤2/3/4并行）。
  执行流程：
  1. 步骤1：调用 call_versatile 获取企业基础信息（baseInfo）
  2. 步骤2/3/4：拿到 baseInfo 后，同轮调3个 call_versatile 并行执行
     - 信贷综合金融
     - 信贷综合分析
     - 尽调要点
  由主Agent通过 call_subagent 调度，不直接响应用户请求。
  前置条件：必须已确认 customer_name（客户名称）。
---

# corporate_credit_123_sub_skill

## 职责

作为子Agent的执行Skill，接收主Agent分发的单企业分析任务，先串行执行步骤1获取baseInfo，再同轮调3个 `call_versatile` 并行执行步骤2/3/4完成分析。

## 工具白名单

- `call_versatile`
- `ask_user`
- `cancel_task`

## 执行流程

### 第一步：提取企业名称

从主Agent分发的 `query` 字段中提取企业名称作为 `customer_name`。
query 格式："对{企业名称}进行公司信贷123信息提取" → 提取 {企业名称}。
优先从 query 直接提取，仅在信息严重缺失时使用 ask_user 追问。

### 第二步：调用基础信息检索（步骤1，串行前置）

调用第一个工作流获取企业基础数据：

```
call_versatile(
  query_description="{customer_name}",
  query_intent="基本信息抽取"
)
```

**关键**：LLM 需将 `baseInfo` 暂存于 thought 中，用于步骤2/3/4的并行调用。

### 第三步：并行调用3个工作流（步骤2/3/4，同轮多调）

拿到 baseInfo 后，**同轮一次性**调用3个 `call_versatile`：

```
call_versatile(
  query_description="$${customer_name}$${baseInfo}$$",
  query_intent="信贷综合金融"
)
call_versatile(
  query_description="$${customer_name}$${baseInfo}$$",
  query_intent="信贷综合分析"
)
call_versatile(
  query_description="$${customer_name}$${baseInfo}$$",
  query_intent="尽调要点"
)
```

**注意**：
- 3个工作流必须**同轮一次性并行调用**，不得串行
- `query` 格式：`$${customer_name}$${baseInfo}$$`


## 最终答案要求

子 Agent 在所有 `call_versatile` 调用返回后，必须将每个工作流的**实际返回结果**写入 `final_answer`。


## 强约束

1. **步骤顺序**：必须先完成步骤1获取 baseInfo，再执行步骤2/3/4
2. **并行要求**：步骤2/3/4 必须同轮一次性调用3个 `call_versatile`，禁止串行
3. **数据完整性**：步骤2/3/4 的 query 中必须包含步骤1返回的 baseInfo，不得编造
4. **禁止递归**：本 Skill 不可调用 `call_subagent`
5. **结果透传**：子 Agent 必须严格原样透传 `call_versatile` 返回的业务数据
   - final_answer 中仅拼接4个工作流的原始返回值，按"基本信息/信贷综合金融/信贷综合分析/尽调要点"顺序排列
   - 禁止添加"需求概述"、"规划过程"、"任务执行情况"等框架性描述
   - 禁止添加"以上分析内容由AI生成"等免责声明
   - 禁止对工作流返回内容做摘要、裁剪、二次加工
   - 禁止添加任何分析、评价、总结或建议内容
