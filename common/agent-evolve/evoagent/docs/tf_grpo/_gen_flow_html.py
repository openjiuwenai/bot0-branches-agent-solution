# -*- coding: utf-8 -*-
"""Generate TF-GRPO process HTML from balanced30 run 69ee31b9d5d5."""
from __future__ import annotations

import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNIP = Path(__file__).with_name("_run_snippets.json")
OUT = Path(__file__).with_name("TF-GRPO优化流程说明-balanced30-69ee31b9d5d5.html")
ART = ROOT / "workspace/artifacts/69ee31b9d5d5"


def esc(s: str) -> str:
    return html.escape(s or "", quote=False)


def pre(s: str, limit: int | None = None) -> str:
    t = s if limit is None else (s[:limit] + ("\n…（已截断）" if len(s) > limit else ""))
    return f"<pre class='code'>{esc(t)}</pre>"


def main() -> None:
    snippets = json.loads(SNIP.read_text(encoding="utf-8"))
    gates = {
        i: json.loads((ART / f"epoch_{i}/gate_result.json").read_text(encoding="utf-8"))
        for i in (1, 2, 3)
    }
    skill_before = (ART / "epoch_0/skill_before.md").read_text(encoding="utf-8")
    skill_e2 = (ART / "epoch_2/skill_after.md").read_text(encoding="utf-8")

    # 源码真实模板（占位符保留），见 variant_generator / semantic_advantage
    variant_prompt_skeleton = """请对这份技能文档（SKILL.md）生成一个**多样化**的改进变体。

**经验库状态：** 空（尚无历史经验）。          ← Epoch1
—— 或 ——
**已学习经验（请优先吸收…）：** {经验库文本}   ← Epoch2+

**当前最优版本：**
{current_best 完整 SKILL.md}

**多样性要求（所有变体必须遵守）：**
- 相对当前版本，必须有实质性差异…
- 同一轮多个变体应走不同改进轴（判定门槛 / 流程易错点 / 输出自检）…
**其它要求：** Frontmatter 保持不变；只输出完整 SKILL.md；禁止截断
**轮次提示：** 第 {epoch} 轮

优化后的 SKILL.md：
"""

    summary_prompt_skeleton = """请总结该技能文档变体的一次 rollout，供后续组内相对比较使用。

**变体 id：** e1-g1
**平均分：** 0.5000（共 8 条用例）

**技能变体（SKILL.md）：**
{变体全文}

**各用例结果：**
**用例 case-059**（得分=1.0000）
- 期望: {"responsibility":"无责", ...}
- 预测/输出: {"responsibility":"无责", ...}
…

请写一份约 8–12 条要点的简洁摘要…
Rollout 摘要：
"""

    advantage_prompt_skeleton = """请分析多次技能优化尝试，提炼关键洞察。

（经验库为空。） / 或已有条目

**当前 Rollout 组：**
**变体 e1-g1**（得分: 0.5000）：{摘要}
**变体 e1-g2**（得分: 0.6250）：{摘要}

请提炼 2–3 条关于「何种变体更成功」的关键洞察…
关键洞察：
"""

    library_prompt_skeleton = """请管理技能优化用的经验库。

**当前经验库：**
（空）

**新洞察：**
{semantic_advantage 全文}

决定如何更新经验库。聚焦可执行、具体的指导。
操作类型：Add / Delete / Modify / Keep
仅输出 JSON 列表：
[{"operation":"Add","content":"..."}, ...]

操作：
"""

    vh = snippets["variant_heads"]
    variant_head = vh[1] if len(vh) > 1 else vh[0]
    counts = esc(str(snippets["counts"]))

    body = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>TF-GRPO 优化流程说明 · balanced30 · 69ee31b9d5d5</title>
<style>
:root {{
  --bg: #0b1220;
  --panel: #121a2b;
  --panel2: #182235;
  --ink: #e8eef7;
  --muted: #9aa8bc;
  --line: #2a3950;
  --accent: #3db8a0;
  --accent2: #5b8def;
  --warn: #e0a35a;
  --bad: #e07171;
  --ok: #5dcc8a;
  --mono: "Cascadia Code","Sarasa Mono SC",Consolas,monospace;
  --sans: "Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
}}
* {{ box-sizing: border-box; }}
body {{
  margin: 0; color: var(--ink); font-family: var(--sans); line-height: 1.6;
  background:
    radial-gradient(900px 420px at 8% -8%, rgba(61,184,160,.18), transparent 55%),
    radial-gradient(700px 360px at 100% 0%, rgba(91,141,239,.14), transparent 50%),
    var(--bg);
}}
.wrap {{ max-width: 1080px; margin: 0 auto; padding: 36px 18px 72px; }}
header {{ border-bottom: 1px solid var(--line); padding-bottom: 22px; margin-bottom: 24px; }}
.eyebrow {{ color: var(--accent); font-size: 12px; letter-spacing: .12em; text-transform: uppercase; font-weight: 700; }}
h1 {{ font-size: clamp(1.6rem, 3vw, 2.2rem); margin: 8px 0 10px; letter-spacing: -.02em; }}
.sub {{ color: var(--muted); max-width: 52rem; margin: 0; }}
.meta {{ display:flex; flex-wrap:wrap; gap:8px; margin-top:16px; }}
.chip {{ background: var(--panel); border:1px solid var(--line); border-radius:999px; padding:4px 12px; font-size:13px; color:var(--muted); }}
.chip strong {{ color: var(--ink); }}
.grid {{ display:grid; grid-template-columns: repeat(4,1fr); gap:10px; margin:20px 0; }}
@media (max-width:820px) {{ .grid {{ grid-template-columns: repeat(2,1fr); }} }}
.stat {{ background: var(--panel); border:1px solid var(--line); border-radius:12px; padding:14px 14px 12px; }}
.stat .k {{ color: var(--muted); font-size:12px; }}
.stat .v {{ font-size:1.35rem; font-weight:700; margin-top:4px; }}
.ok {{ color: var(--ok); }} .warn {{ color: var(--warn); }} .bad {{ color: var(--bad); }}
nav.toc {{ background: var(--panel); border:1px solid var(--line); border-radius:12px; padding:14px 18px; margin:18px 0 28px; }}
nav.toc a {{ color: var(--accent2); text-decoration:none; margin-right:14px; font-size:14px; }}
nav.toc a:hover {{ text-decoration:underline; }}
section {{ background: var(--panel); border:1px solid var(--line); border-radius:14px; padding:20px 20px 16px; margin:0 0 18px; }}
section h2 {{ margin:0 0 8px; font-size:1.2rem; }}
section h3 {{ margin:18px 0 8px; font-size:1.02rem; color: var(--accent); }}
p.lead {{ color: var(--muted); margin:0 0 12px; }}
table {{ width:100%; border-collapse:collapse; font-size:14px; margin:10px 0 6px; }}
th, td {{ border:1px solid var(--line); padding:8px 10px; vertical-align:top; text-align:left; }}
th {{ background: var(--panel2); color: var(--muted); font-weight:600; width:18%; }}
.flow {{
  font-family: var(--mono); font-size:12.5px; white-space:pre; overflow:auto;
  background:#0a101c; border:1px solid var(--line); border-radius:10px; padding:14px; color:#c9d6e8;
}}
.code {{
  font-family: var(--mono); font-size:12px; white-space:pre-wrap; word-break:break-word;
  background:#0a101c; border:1px solid var(--line); border-radius:10px; padding:12px 14px;
  color:#d7e2f2; max-height:420px; overflow:auto; margin:8px 0 4px;
}}
.tag {{ display:inline-block; font-size:11px; border-radius:6px; padding:2px 8px; margin-left:6px; border:1px solid var(--line); color:var(--muted); }}
.tag.llm {{ color:#9ec5ff; border-color:#35507a; background:#152338; }}
.tag.io {{ color:#9fe7d2; border-color:#2d6a5c; background:#132820; }}
.tag.real {{ color:#ffd59a; border-color:#7a5a2d; background:#2a2114; }}
.callout {{ border-left:3px solid var(--accent); padding:8px 12px; background:rgba(61,184,160,.08); border-radius:0 8px 8px 0; color:var(--muted); font-size:14px; margin:10px 0; }}
footer {{ color: var(--muted); font-size:13px; margin-top:28px; border-top:1px solid var(--line); padding-top:14px; }}
code {{ font-family: var(--mono); font-size: 0.92em; }}
</style>
</head>
<body>
<div class="wrap">
<header>
  <div class="eyebrow">EvoAgent · TF-GRPO Walkthrough</div>
  <h1>TF-GRPO 优化流程说明（含提示词与 LLM I/O）</h1>
  <p class="sub">以已跑通的 <strong>audit_business_balanced30</strong>（30 条：train 20 / val 10）实验
  <code>run_id=69ee31b9d5d5</code> 为例，按阶段说明输入输出，并摘录真实 LLM 输出。</p>
  <div class="meta">
    <span class="chip"><strong>日期</strong> 2026-07-17</span>
    <span class="chip"><strong>模型</strong> qwen3.7-max</span>
    <span class="chip"><strong>Agent</strong> edp_agent</span>
    <span class="chip"><strong>Skill</strong> audit-business</span>
    <span class="chip"><strong>耗时</strong> ≈40 min</span>
    <span class="chip"><strong>结果</strong> val 0.70 → 0.90</span>
  </div>
</header>

<div class="grid">
  <div class="stat"><div class="k">基线 Val</div><div class="v warn">0.70</div></div>
  <div class="stat"><div class="k">最终最佳 Val</div><div class="v ok">0.90</div></div>
  <div class="stat"><div class="k">Epochs / Group</div><div class="v">3 / 3</div></div>
  <div class="stat"><div class="k">每轮 Train 子集</div><div class="v">8 cases</div></div>
</div>

<nav class="toc">
  <a href="#overview">总览</a>
  <a href="#s0">装配</a>
  <a href="#s1">基线</a>
  <a href="#s2">TF-GRPO Epoch</a>
  <a href="#llm">LLM 调用</a>
  <a href="#gate">Val 门控</a>
  <a href="#timeline">本 run 时间线</a>
  <a href="#artifacts">产物</a>
</nav>

<section id="overview">
  <h2>1. 流程总览</h2>
  <p class="lead">交付主路径为服务化 <code>POST /optimize</code>（scenario=<code>tf_grpo</code>）；本页实证 run 当时用同源 CLI。链路：三容器联调 → 基线 Val → 3 个 TF-GRPO epoch → Val 门控采纳。</p>
  <div class="flow"># 服务化（交付）
POST /optimize  {{ optimizer_template.scenario: "tf_grpo", dataset_path: ".../cases.json", ... }}
GET  /optimize/{{job_id}} | /stream | POST .../cancel

# CLI 同源（本 run 实证）
run_optimize.py --scenario tf_grpo --dataset-manifest .../balanced30/dataset.yaml
                 --agent-name edp_agent --epochs 3 --adapter-url http://127.0.0.1:18900

[装配] restore_skill → operators → RemoteAgent → dataset(train20/val10)
   │
   ▼
[基线 Val] 10 cases → score=0.70  （缓存 base）
   │
   ├─ Epoch1: sample 8 → e1-g1(0.50) e1-g2(0.625) e1-g3(fail)
   │           best=e1-g2 → Val keep base (0.70=0.70)
   ├─ Epoch2: sample 8 → best=e2-g1(train 0.75) → Val adopt (0.90)
   └─ Epoch3: sample 8 → best=e3-g1(train 0.75) → Val keep (0.90=0.90)
最终最佳验证分数 0.900</div>
  <div class="callout">超参（scenario.yaml）：group_size=3，cases_per_variant=8，variant_temperature=1.5，max_experiences=20，num_parallel=4。Evaluator：exact_match + answer_tag_json_field（responsibility / responsibility_type）。</div>
</section>

<section id="s0">
  <h2>2. 阶段 0：启动装配 <span class="tag io">I/O</span></h2>
  <table>
    <tr><th>输入</th><td>服务化：<code>POST /optimize</code>（scenario=<code>tf_grpo</code>、<code>dataset_path</code>；<code>evaluator_template.type</code> 默认 <code>metric</code>，可 <code>extract</code>；语义评估显式 <code>type=llm</code>）；或 CLI + <code>dataset.yaml</code> evaluator。<code>scenarios/tf_grpo/scenario.yaml</code>；<code>.env</code>（<code>EVO_ADAPTER_URL</code> / <code>EVO_LLM_*</code>）</td></tr>
    <tr><th>处理</th><td>JobManager → <code>run_optimization</code>：AdapterClient → <code>restore_skill(['audit-business'])</code> → operators → 划分 train/val</td></tr>
    <tr><th>输出</th><td>API 返回 <code>job_id</code>；内部 <code>run_id=69ee31b9d5d5</code>；<code>TfGrpoOptimizer</code> + <code>EvoTrainer</code></td></tr>
  </table>
</section>

<section id="s1">
  <h2>3. 阶段 1：基线 Val <span class="tag io">I/O</span></h2>
  <table>
    <tr><th>输入</th><td>restore 后的初始 <code>audit-business</code> SKILL；完整 val 10 条；EDPAgent（AgentRule_audit_business）</td></tr>
    <tr><th>处理</th><td>对每条 val：新 conversation_id → Adapter 对话 → cleaned-traces → metric 打分</td></tr>
    <tr><th>输出</th><td class="ok"><strong>score=0.70</strong>（约 7/10）；<code>record_validation_baseline</code> 缓存，供后续 gate 复用 base</td></tr>
  </table>
  <div class="callout">冒烟错例方向：005/064/062 等「期望有责 → 预测无责」（见测试报告 TC-06）。</div>
</section>

<section id="s2">
  <h2>4. 阶段 2：单个 TF-GRPO Epoch（核心）</h2>
  <p class="lead">每个 Trainer epoch = 一次 <code>_backward</code>。以下以 Epoch 1 为主说明；Epoch 2/3 结构相同。</p>

  <h3>4.1 采样 train 子集</h3>
  <table>
    <tr><th>输入</th><td>train_cases（20）；cases_per_variant=8；seed=artifact_epoch+1</td></tr>
    <tr><th>输出</th><td>本轮共享的 8 条 case（组内 G 个变体共用，避免不公平对比）</td></tr>
  </table>

  <h3>4.2 冻结经验上下文</h3>
  <table>
    <tr><th>输入</th><td>进程内 ExperienceLibrary（Epoch1 开始为空）</td></tr>
    <tr><th>输出</th><td><code>experience_context</code> 文本；本轮变体生成全部用这份冻结上下文</td></tr>
  </table>

  <h3>4.3 变体循环 g=1..3（串行）</h3>
  <p>每步：生成 → 完整性校验 → 热更 → rollout 打分 → 摘要。本 run Epoch1：</p>
  <table>
    <tr><th>变体</th><th>Train 均分</th><th>备注</th></tr>
    <tr><td>e1-g1</td><td>0.500</td><td>有完整摘要（见下）</td></tr>
    <tr><td>e1-g2</td><td class="ok"><strong>0.625</strong></td><td>组内胜出；引入词典 A/B 等</td></tr>
    <tr><td>e1-g3</td><td class="bad">—</td><td>生成失败（incomplete），已 skip</td></tr>
  </table>

  <h3>4.4 热更 + Rollout（业务 Agent，非优化器 LLM）</h3>
  <table>
    <tr><th>输入</th><td>变体 SKILL.md；8 条 case query；新 conversation_id</td></tr>
    <tr><th>处理</th><td><code>update_skill</code> → EDP <code>read_file(/tmp/skills/audit-business/SKILL.md)</code> + <code>execute_cmd(working_days.py)</code> → 吐 <code>&lt;answer&gt;JSON&lt;/answer&gt;</code></td></tr>
    <tr><th>输出</th><td>每 case 的 answer + traces；evaluator 均分；无 trace 的 case 不计分</td></tr>
  </table>

  <h3>4.5 经验库更新（组结束后）</h3>
  <table>
    <tr><th>输入</th><td>本组有效 RolloutSummary 列表（需分数有方差）</td></tr>
    <tr><th>处理</th><td>LLM 语义优势 → LLM 产出 Add/Modify/Delete/Keep JSON → apply</td></tr>
    <tr><th>输出</th><td>更新后的经验库（供下一 epoch 注入变体 prompt）</td></tr>
  </table>
</section>

<section id="llm">
  <h2>5. LLM 调用详解（优化器侧） <span class="tag llm">Prompt / Response</span></h2>
  <p class="lead">一轮 epoch 内，优化器 LLM（qwen3.7-max）主要出现四类调用。下列「输入骨架」来自源码；「输出摘录」来自本 run 日志解析（{counts}）。</p>

  <h3>5.1 变体生成 <span class="tag real">真实输出摘录</span></h3>
  <table>
    <tr><th>何时</th><td>每个 g：<code>_generate_variant</code></td></tr>
    <tr><th>LLM 输入</th><td>{pre(variant_prompt_skeleton)}</td></tr>
    <tr><th>LLM 输出</th><td>完整 SKILL.md（frontmatter 由代码 restore）。Epoch1 成功变体开头：</td></tr>
  </table>
  {pre(variant_head, 900)}
  <div class="callout">e1-g2 相对基线增强了「前置拦截门 / 责任硬标记词典」等结构；e1-g3 因不完整被拒。完整模板见 <code>variant_generator.build_variant_prompt</code>。</div>

  <h3>5.2 Rollout 摘要 <span class="tag real">真实输出</span></h3>
  <table>
    <tr><th>何时</th><td>每个有效变体打分后：<code>_summarize_rollout</code></td></tr>
    <tr><th>LLM 输入骨架</th><td>{pre(summary_prompt_skeleton)}</td></tr>
    <tr><th>e1-g1 输出</th><td>train≈0.50 的摘要：</td></tr>
  </table>
  {pre(snippets["first_summary"], 1800)}
  <p>e1-g2 输出（train≈0.625）：</p>
  {pre(snippets["second_summary"], 1800)}

  <h3>5.3 语义优势 <span class="tag real">真实输出</span></h3>
  <table>
    <tr><th>何时</th><td>组内 ≥2 个有效变体且分数有方差：<code>build_semantic_advantage_prompt</code></td></tr>
    <tr><th>LLM 输入骨架</th><td>{pre(advantage_prompt_skeleton)}</td></tr>
    <tr><th>Epoch1 输出</th><td>对比 e1-g1 vs e1-g2：</td></tr>
  </table>
  {pre(snippets["first_advantage"], 2200)}

  <h3>5.4 经验库操作 JSON <span class="tag real">真实输出</span></h3>
  <table>
    <tr><th>何时</th><td>语义优势之后：<code>build_library_update_prompt</code></td></tr>
    <tr><th>LLM 输入骨架</th><td>{pre(library_prompt_skeleton)}</td></tr>
    <tr><th>Epoch1 输出</th><td>三条 Add（词典化、解耦时限与责任、无责前复核）：</td></tr>
  </table>
  {pre(snippets["first_ops"], 1600)}
  <div class="callout">这些经验在 Epoch2 变体生成时注入 prompt，推动 candidate 在完整 val 上从 0.70 升到 0.90。</div>

  <h3>5.5 两类 LLM 的分工</h3>
  <table>
    <tr><th>优化器 LLM</th><td>qwen3.7-max：变体生成 / 摘要 / 语义优势 / 经验库 JSON（本节 5.1–5.4）</td></tr>
    <tr><th>业务 Agent LLM</th><td>EDPAgent 在 jiuwenbox 内读 SKILL + 调 <code>working_days.py</code>，产出 <code>&lt;answer&gt;JSON&lt;/answer&gt;</code>；不直接改 skill</td></tr>
  </table>
</section>

<section id="gate">
  <h2>6. 阶段 3：Validation Gate <span class="tag io">I/O</span> <span class="tag real">真实 gate_result</span></h2>
  <table>
    <tr><th>输入</th><td>epoch 初 base Skill；组内胜出 candidate；完整 val 10 条</td></tr>
    <tr><th>处理</th><td>base 可用缓存；candidate 全量评估；比较分数决定采纳</td></tr>
    <tr><th>输出</th><td><code>epoch_N/gate_result.json</code> + 正式 Skill 状态</td></tr>
  </table>
  <table>
    <tr><th>Epoch</th><th>base</th><th>candidate</th><th>improvement</th><th>decision</th></tr>
    <tr><td>1</td><td>{gates[1]["base_score"]}</td><td>{gates[1]["candidate_score"]}</td><td>{gates[1]["improvement"]}</td><td class="warn">{gates[1]["decision"]}</td></tr>
    <tr><td>2</td><td>{gates[2]["base_score"]}</td><td class="ok">{gates[2]["candidate_score"]}</td><td class="ok">+{gates[2]["improvement"]:.2f}</td><td class="ok">{gates[2]["decision"]}</td></tr>
    <tr><td>3</td><td>{gates[3]["base_score"]}</td><td>{gates[3]["candidate_score"]}</td><td>{gates[3]["improvement"]}</td><td>{gates[3]["decision"]}</td></tr>
  </table>
  <p>Epoch2 采纳后的 Skill 相对初始基线，强化了解耦时限/责任与结构化词典（文件头摘录）：</p>
  {pre(skill_e2[:1200], 1200)}
</section>

<section id="timeline">
  <h2>7. 本 run 时间线（日志要点）</h2>
  <div class="flow">00:44:39  pipeline_start / restore_skill audit-business
00:44:59  Baseline evaluation completed  score=0.7
00:45:xx  Epoch1 TF-GRPO group_size=3 cases=8
          e1-g1 summary(0.50) → e1-g2 summary(0.625) → e1-g3 generation failed
          semantic advantage + library Add×3
          [tf_grpo_done] best e1-g2 score=0.6250
          Val gate → keep base (0.70)
…         Epoch2 best e2-g1 train=0.750 → Val adopt candidate (0.90)
…         Epoch3 best e3-g1 train=0.750 → Val keep (0.90)
01:24:xx  训练结束：最佳验证分数 0.900</div>
</section>

<section id="artifacts">
  <h2>8. 产物与对照文件</h2>
  <table>
    <tr><th>路径</th><th>说明</th></tr>
    <tr><td><code>workspace/artifacts/69ee31b9d5d5/</code></td><td>本 run 根目录</td></tr>
    <tr><td><code>epoch_*/skill_before.md / skill_after.md</code></td><td>每轮基线与胜出 Skill</td></tr>
    <tr><td><code>epoch_*/gate_result.json</code></td><td>门控分数与 decision</td></tr>
    <tr><td><code>experiment_report.html</code></td><td>同目录实验报告（指标视角）</td></tr>
    <tr><td><code>optimize_balanced30_run.log</code></td><td>含完整 LLM response_content</td></tr>
    <tr><td><code>src/.../variant_generator.py</code></td><td>变体 prompt 模板</td></tr>
    <tr><td><code>src/.../semantic_advantage.py</code></td><td>摘要 / 优势 / 库操作 prompt</td></tr>
  </table>
  <div class="callout">初始基线 Skill 开头（epoch_0/skill_before.md）：</div>
  {pre(skill_before[:700], 700)}
</section>

<footer>
  文档生成自 run <code>69ee31b9d5d5</code> 产物与源码提示词模板；LLM 原文摘自
  <code>workspace/artifacts/optimize_balanced30_run.log</code> 解析结果。
  中间淘汰变体默认不落盘，完整变体正文以日志 / skill_after 为准。
</footer>
</div>
</body>
</html>
"""
    OUT.write_text(body, encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
