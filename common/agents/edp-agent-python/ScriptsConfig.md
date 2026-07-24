---
# 话术配置文件
# 该文件包含 EDPAgent 的所有话术配置
#
# Phase2 解耦优化：
# - 通用话术（13 项）保留在 scripts: 块中
# - 业务话术已迁移到各 skills/<skill>/SKILL.md 的 scripts: 字段
# - 业务话术由 collect_skill_scripts() 在 initialize_dpa() 中收集到 ScriptsConfig.extra_scripts

# 通用话术（用于工具调用、todolist 等，框架级）
scripts:
  tool_start: "正在调用：{tool_name}"
  tool_end: "{tool_name} 执行完成"
  todo_start: "开始执行：{title}"
  todo_end: "{title} 已完成"
  todolist_start: "规划任务清单"
  todolist_end: "todolist规划完成"
  interrupt_start: "需要您确认以下信息"
  request_start: "您的请求已收到。"
  planning_start: "我们正在为您进行规划。"
  task_cancelled: "好的，已为您取消当前操作。如需其他帮助，请随时告诉我。"
  cancel_confirm: "确认要取消当前操作吗？"
  out_of_scope: "正在学习中，暂不支持该业务。"
  mcp_result_empty: "根据您的条件没有找到合适产品，您可以从以下产品中选择一个或者重新筛选。"

# think_chunk 推送模式配置
# real_stream: 真实 LLM 流式 shard 直接推送前端
# fixed_script: 用预定义固定话术帧替代 LLM shard 推送前端
think_chunk_mode: real_stream

# 固定话术帧配置（仅 think_chunk_mode=fixed_script 时生效）
think_chunk_fixed_scripts:
  enabled: true
  chars_per_frame: 4          # 每帧4字符（逐字渲染模式，0=不切分整句推送）
  tokens_between_frames: 2    # 每累积2个LLM token推送一个子帧
  min_interval_ms: 50         # 子帧间最少间隔50ms（0=不限速）

  # ── planning 阶段话术（第1轮思考）──────────────────────────────
  # 默认话术（query_patterns 全未命中时降级使用）
  default_scripts:
    - "正在分析您的需求..."
  # 通用关键词匹配（框架级兜底，当场景配置未命中时使用）
  # 注意：此为通用匹配，业务专属关键词请配置到场景文件 query_patterns 中
  query_patterns:
    # 通用推荐类关键词（跨场景通用）
    - keywords: ["推荐", "看看", "有什么"]
      scripts:
        - "正在为您搜索相关内容..."
    # 通用购买/确认类关键词（跨场景通用）
    - keywords: ["购买", "买", "下单", "确认"]
      scripts:
        - "正在确认相关信息..."
    # 通用查询类关键词（跨场景通用）
    - keywords: ["查询", "查看", "搜索", "了解"]
      scripts:
        - "正在为您查询相关信息..."

  # ── executing 阶段话术（第2轮及后续思考轮次）────────────────────
  # 当 Agent 在执行工具后进入反思/决策思考时使用
  execution_scripts:
    - "正在分析执行结果..."

  # ── resuming 阶段话术（Cascade 续轮，query="continue"）─────────
  # 当用户在中断后回复触发续轮时使用
  # enable_resume_scripts: 是否启用 resuming 阶段固定话术（默认 true）
  # 设为 false 时，resuming 阶段不输出固定话术
  enable_resume_scripts: false
  resume_scripts:
    - "当前业务步骤已为您处理完毕"

  # 保留 scripts 字段（向后兼容：以上所有字段均为空时降级使用）
  scripts:
    - "正在分析您的需求..."
---
