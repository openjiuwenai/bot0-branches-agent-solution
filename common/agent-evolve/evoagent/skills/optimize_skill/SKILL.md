---
name: optimize_skill
description: >
  优化指定场景下的 skill 文档。当用户想要提升 skill 效果时使用。
  接收场景名称和 dataset manifest，自动运行多轮 ReflACT 优化并输出报告。
tools:
  - bash_tool
  - read_file
  - write_file
---

# Optimize Skill

优化指定场景下的 skill 文档，通过远程 ReflACT pipeline 进行多轮迭代。

## 执行步骤

1. **解析用户指令**，提取以下参数：
   - 场景名称（必填，如 `edp_agent`，对应 `examples/scenarios/<name>/scenario.yaml`）
   - dataset manifest 路径（必填，`dataset.yaml`）
   - Adapter 地址（可选，默认从 scenario.yaml 读取）
   - Agent 名称（可选，默认使用场景名称）
   - 优化轮数（可选，默认 3）
   - batch size（可选，默认 4）

2. **查看场景配置**（可选，用于确认参数）：
   ```bash
   cat examples/scenarios/<场景名称>/scenario.yaml
   ```

3. **执行优化脚本**：
   ```bash
   python skills/optimize_skill/scripts/run_optimize.py \
     --scenario <场景名称> \
     --dataset-manifest <path/to/dataset.yaml> \
     --epochs 3 \
     --batch-size 4
   ```

   如果用户显式指定了 adapter 地址或 agent 名称，追加参数：
   ```bash
   python skills/optimize_skill/scripts/run_optimize.py \
     --scenario <场景名称> \
     --dataset-manifest <path/to/dataset.yaml> \
     --adapter-url <用户指定的地址> \
     --agent-name <用户指定的名称> \
     --epochs 3 \
     --batch-size 4
   ```

   如果用户指定了要优化的 skill 列表（逗号分隔）：
   ```bash
   --skills skill_a,skill_b
   ```

4. **向用户汇报结果**，包括：
   - 优化前后得分对比（overall + per-skill）
   - 应用的编辑数量
   - 产物目录路径
   - 各 skill 的独立得分（如有）

5. **处理错误**：如果脚本退出码非 0，向用户报告 stderr 中的错误信息，
   常见原因包括：adapter_url 未配置或格式无效、scenario.yaml 不存在、dataset manifest 不存在等。
