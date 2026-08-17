# Trajectory Reader

## 描述
解析轨迹文件格式，理解 StandardTrajectory 结构和消息流。

## 可执行脚本
`parse_trajectory.py` — 解析 trajectory.jsonl 并输出结构化摘要。

```bash
python3 parse_trajectory.py                     # 全局摘要
python3 parse_trajectory.py --role tool         # 仅 tool 消息
python3 parse_trajectory.py --role assistant    # 仅 assistant 消息
python3 parse_trajectory.py --tool call_versatile  # 特定工具的调用和返回
python3 parse_trajectory.py --line 5            # 第 5 条消息的完整 JSON
python3 parse_trajectory.py --help              # 查看全部选项
```

## 能力
- 解析 `trajectory.jsonl`（每行一条 JSON 消息）和 `trajectory.md`（压缩摘要）
- 识别消息角色：`user`、`assistant`、`system`、`tool`
- 提取工具调用（`tool_calls`）和工具返回（`role="tool"`）
- 理解多轮对话的上下文流转

## 使用场景
所有维度评估前，先用此 skill 理解轨迹结构和消息流，定位关键节点（用户请求、Agent 回复、工具调用）。

## 工作目录文件
- `trajectory.jsonl` — 全量消息流（一行一条 JSON）
- `trajectory.md` — 压缩摘要（嵌入 prompt 的精简视图）

## 读取建议
- 先 `python3 parse_trajectory.py` 获取全局概览
- 需要特定消息时 `python3 parse_trajectory.py --line N`
- 需要精确定位时 `Grep trajectory.jsonl "关键词"`
