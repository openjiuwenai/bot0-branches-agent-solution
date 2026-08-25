# Trajectory Cleaner

## 描述
对原始轨迹进行清洗和标准化，消除噪声数据，使评估基于干净的输入。

## 可执行脚本
`clean_trajectory.py` — 清洗 trajectory.jsonl 并输出 cleaned_trajectory.jsonl。

```bash
python3 clean_trajectory.py                              # 默认清洗
python3 clean_trajectory.py --max-tool-chars 3000        # 自定义 tool 截断阈值
python3 clean_trajectory.py --output my_clean.jsonl      # 自定义输出路径
python3 clean_trajectory.py --help                       # 查看全部选项
```

## 清洗规则
1. **系统消息**：保留首条 `system` 消息（Agent 指令），过滤后续重复的系统注入
2. **工具返回**：超过 2000 字符的 `role="tool"` 消息截断，保留头部 + `...[truncated N → kept first K]`
3. **空消息**：`content` 为空字符串且无 `tool_calls` 的消息直接跳过

## 使用场景
评估前先用 `python3 clean_trajectory.py` 生成清洗后的轨迹，再基于 `cleaned_trajectory.jsonl` 评估，避免噪声数据影响评分准确性。
