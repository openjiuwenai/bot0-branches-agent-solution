# Trajectory Decompressor

## 描述
从压缩轨迹回溯原文，进行深入分析。当 `trajectory.md`（压缩摘要）信息不足时，用此 skill 定位并读取 `trajectory.jsonl` 中的原始消息。

## 可执行脚本
`expand_search.py` — 搜索 trajectory.jsonl 并展开完整上下文。

```bash
python3 expand_search.py "call_versatile"              # 搜索所有消息
python3 expand_search.py "call_versatile" --role tool  # 仅搜索 tool 返回
python3 expand_search.py --context 5,10                # 展开第 5-10 条消息的完整内容
python3 expand_search.py --tool-call                   # 列出所有工具调用
python3 expand_search.py --help                        # 查看全部选项
```

## 使用流程
1. **先读摘要**：`Read trajectory.md` 获取全局上下文
2. **定位缺口**：识别摘要中信息不足的部分（如「调用了某工具」但没给参数）
3. **回溯原文**：`python3 expand_search.py "工具名"` 搜索并展开完整消息
4. **补充分析**：将原文细节纳入评估依据

## 典型场景
- 摘要说「Agent 调用了 call_versatile」→ `python3 expand_search.py "call_versatile"` 查看参数
- 摘要说「返回了 3 款产品」→ `python3 expand_search.py "产品" --role tool` 查看返回详情
- 需要查看某段对话上下文 → `python3 expand_search.py --context 5,10`
