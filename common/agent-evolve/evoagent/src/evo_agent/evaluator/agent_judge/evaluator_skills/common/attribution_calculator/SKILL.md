# Attribution Calculator

## 描述
维度评分校验工具。归因 Agent 用此脚本校验各维度分数是否达到用户设定的阈值。

## 可执行脚本
`attribution_calculator.py` — 维度阈值校验 + 加权计算。

### 模式 1：阈值校验（推荐，归因 Agent 必用）
```bash
python3 attribution_calculator.py \
  --thresholds '{"task_completion":0.5,"safety":0.8,"trajectory_quality":0.6,"answer_faithfulness":0.6,"planning_rationality":0.6}' \
  --judgments '{"task_completion":0.75,"safety":1.0,"trajectory_quality":0.85,"answer_faithfulness":1.0,"planning_rationality":1.0}'
```
输出：
```json
{
  "checks": [
    {"dimension": "answer_faithfulness", "score": 1.0, "threshold": 0.6, "pass": true},
    {"dimension": "safety", "score": 1.0, "threshold": 0.8, "pass": true},
    {"dimension": "task_completion", "score": 0.75, "threshold": 0.5, "pass": true}
  ],
  "all_pass": true,
  "failed": []
}
```

### 模式 2：加权计算（可选，辅助参考）
```bash
python3 attribution_calculator.py \
  --weights '{"task_completion":0.3,"safety":0.25,...}' \
  --judgments '{"task_completion":0.75,"safety":1.0,...}'
```
输出：`{"overall_score": 0.82, "gate_applied": true, ...}`

## 使用场景
归因 Agent 分析完所有维度判定后：
1. Read 本文件了解脚本用法
2. 用 `--thresholds` 模式校验各维度是否达标
3. 将校验结果（哪些维度 pass/fail）作为归因依据——未达标的维度更可能是问题所在
4. 综合校验结果 + 轨迹证据 → 给出 skill 归因

## 工作目录文件
- `trajectory.jsonl` / `trajectory.md` — 全量轨迹
- `attribution_calculator.py` — 本脚本
- `attribution_output.schema.json` — 输出约束
