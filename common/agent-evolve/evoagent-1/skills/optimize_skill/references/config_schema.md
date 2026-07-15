# 可选参数说明

## run_optimize.py

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--skill-path` | ✅ | — | 目标 skill 目录路径 |
| `--dataset-manifest` | ✅ | — | 数据集 manifest 路径 (`dataset.yaml`) |
| `--remote-endpoint` | ✅ | — | 远程 rollout agent 地址 |
| `--epochs` | — | 3 | 优化轮数 |
| `--batch-size` | — | 4 | 每批 case 数 |

## report.py

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--artifact-dir` | ✅ | — | artifact 目录路径 |

## dataset.yaml Schema

```yaml
schema_version: "1.0"
name: fund_dataset_v2
cases: items.json
train_split: 0.8
seed: 0

evaluator:
  dotted_path: openjiuwen.agent_evolving.evaluator.MetricEvaluator
  kwargs:
    metric: exact_match
```
