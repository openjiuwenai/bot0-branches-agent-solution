请管理技能优化用的经验库。

**当前经验库：**
{current_library}

**新洞察（组内对比后的共性执行方向）：**
{semantic_advantage}

决定如何更新经验库。只保留**可执行、具体、能指导下一步变体生成**的指导；合并重复，删掉空泛条目。

操作类型（operation 字段保持英文）：
- Add：新增带具体建议的经验
- Delete：删除空泛或无用的经验（0-based 下标）
- Modify：把某条经验改得更具体（0-based 下标）
- Keep：不做变更

仅输出 JSON 列表：
```json
[
  {"operation": "Add", "content": "补充带输入/输出的具体用法示例"},
  {"operation": "Modify", "index": 0, "content": "补充非法输入等边界情况说明"},
  {"operation": "Delete", "index": 1},
  {"operation": "Keep"}
]
```

操作：
