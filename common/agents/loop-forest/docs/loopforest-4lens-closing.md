# loop-forest 四镜头对抗审查收口报告

> 本报告是轮⑤ closing-prep 六节骨架的实例化（轮⑦产出）。数据全部取自 HEAD `f01425f0` 的可复现证据（git diff / surefire / smoke 复跑），未引用任何不可复核的口头数字。

- **审查对象**：`common/agents/loop-forest`（分支 `feat/loop-forest`）
- **HEAD**：`f01425f0`；fork（gitcode `yaojun97/agent-solution`）的 `feat/loop-forest` 已包含该提交（`git branch -r --contains f01425f0` 实证）
- **对照基线**：`70ffe929`（!381 合入点）——七 commit 链，132 个文件**全部**位于本模块目录，+11189 行、0 删除（本报告自身 commit 后为 133 文件/+11309）（纯新增，零修改任何既有模块）
- **轮次角色**：轮⑥ = 重计后首个干净轮（1/3）；轮⑦ = 本轮（稳定态复扫 + 本报告），干净则 2/3；轮⑧ = 收口终审（3/3）
- **报告日期**：2026-08-27

---

## §1 终态基线

| 维度 | 终态 | 证据 |
|---|---|---|
| 测试 | **105 run / 0 failures / 0 errors / 5 skipped**，BUILD SUCCESS | 轮⑦全量复跑 `mvn test`（surefire 汇总：`Tests run: 105, Failures: 0, Errors: 0, Skipped: 5`） |
| 轮⑧ | 终审 NO-GO：**2 MAJOR**（MR body 数字半对齐 + 报告口头数字'5355 记录于 javadoc'）+ 4 MINOR → 归零 #4；勘误批 a4f32329 + MR body v3-v5 |
| 轮⑨ | 勘误验证：1 MAJOR（MR body 轮次台账少计轮⑧ MAJOR 数）+ 1 MINOR → 归零 #5；单行 PATCH + §4 补行（本 commit）|
| skip 语义 | 5 个 skip = 5 个 env-gated 真 LLM e2e（DeepResearch / VetoSchema / V2Bench / MinimalWrite / AgentSmoke 各 1 例）——诚实边界而非缺陷 | surefire 逐类明细：20 个测试类中 5 个 e2e 类各 1 例 skip |
| 冒烟 | 四行断言度量字段**两次复现一致**（elapsed_ms 为时延观测非断言值，两次运行存在差异）（入库基线 20260827-122036 vs 轮⑦复跑 20260827-231008） | `docs/smoke-baseline-20260827.log` + `logs/smoke-20260827-231008.log` |
| 变更面 | 7 commit / 132 文件 / +11189 / −0，全部位于 `common/agents/loop-forest/` | `git diff --shortstat 70ffe929..f01425f0` |
| 处置台账 | 累计 35 项已处置；余 6 项 MINOR 全部转收口台账，轮⑦零恶化 | 见 §4 / §5 |
| 结构 | 五个主包（verification / observability / rail / fork / search）+ 统一入口 `LoopForestAgent` + bench / e2e 测试基建；20 个测试类 | 源码树 |

模块一句话定位：**长程任务 Agent 的外置纪律与结构（循环森林）**——把写入契约（否决）、预算约束、跨分支收敛、轨迹树与回滚做成宿主 Agent 之外可装配的 rail 与结构，而不是内嵌在某个 Agent 实现里。

## §2 三门（模块的三道纪律门）

三门即三个可装配 rail，全部通过 `GraphLoopRails.registerOnto(agent, config)` 单一装配真源挂载，config 可切换启用/禁用（`GraphLoopRailsGateTest` 对开/关两侧均断言，6 用例）：

1. **否决门 VetoRail**（beforeToolCall）：写入契约——产物零提及被拒事实即拒绝写入。冒烟实证 `attempts=10 intercepted=3 passthrough=7 zero_mention=true`（拦截行为自身也零提及）。
2. **预算门 BudgetRail**：三维预算（束宽 / fork 配额 / token 池）。冒烟实证 `attempts=5 accepted=3 rejected=2`，且 `reason_from_prompts=true`（拒绝文案来自模块自有 prompts 资源，非硬编码字符串）。
3. **收敛门 ConvergenceRail**（afterModelCall）：跨分支确定性择优、归因不替换。冒烟实证 `candidates=6 qualified=4 pruned=5 winner=b-2`。

结构层（非门）：TraceForest 轨迹森林（`branches=5 depth=2 rollback_path=2`）提供树寻址与回滚路径；ForkOrchestrator 负责分叉→森林登记，组合 edpa SubAgentExecutor SPI。

装配语义有锁定测试：`minimalConfigMountsNoConvergenceRail`（mutation-RED 实证——恒空评估器回退会 RED）；统一入口 `LoopForestAgent` 内置踩坑三件（SteeringProvisionRail 必挂 / 轮窗 10→60 / maxTokens 16000 默认），一个 `build()` 得到完整能力。

## §3 数值收益证据链

每条收益都有可复现度量入口：`smoke.sh` A 档确定性度量（无需 env、数值断言入库），B 档 env-gated 真 LLM。

**冒烟四行（两次复现一致）**：数值见 §2 三门 + 结构层一行。

**测试演进链（commit 实证）**：

```
84（1c82f726 初始）
 → 85 → 103（轮①处置 22aeeb81；其中 T1 bench 三件同步治愈 V2Bench 假绿：85→91、skip 4→5）
 → 104（轮②处置 4bc28ade）
 → 104 保持（轮③处置 ad7b3a38）
 → 105（轮④批 e7bcfdce 补装配语义锁定测试）
 → 105 保持（R5-1 真修 f01425f0）
```

84→105 的增量全部来自处置批补的防回归 / 语义锁定 / 装配测试，不是生产代码膨胀的陪跑。

**BLOCKER 修复均带实证，不是"改了就信"**：

- prompts 真源错位：运行时真源寄生于 stale edpa-alpha SNAPSHOT jar——换 jar 实证 16 个测试崩；随后 18 个 .txt `git mv` 进 `prompts/`，并把 `prompts_source=module_owned` 断言进冒烟（永久防复发）。
- pom 缺 pev 声明：fresh clone `javac` 编译必败实证；补显式 `pev 0.1.0`。
- 零冒烟脚本：从无到有 `smoke.sh` + `SmokeMetricsTest`（4 用例数值断言留档入库）。

**迁移等价（真 LLM 双验，59d1a78b）**：B 臂 v2 基准一发——signal 13 轮 / 重锚 11 次 / 跑满 320s / 终态陈述与 goal_signal 一致，与主仓 B 臂同形态，等价成立。

**防回归保护真化**：T2 守卫从恒真断言真化为双语义锁定（自产 signal 回显不自触发 ∧ 注入文本仍触发），mutation 自验剥掉被守护词元后全套件 RED（expected:0 was:1），实证非恒真。

## §4 轮次台账（轮①-⑦）

计数规则（用户令）：连续 3 轮零 BLOCKER / 零 MAJOR 才收口；任一轮出 MAJOR 则计数归零重计。

| 轮 | 发现 | 处置 commit | 计数后果 |
|---|---|---|---|
| ① | 四镜头 NO-GO：**5 BLOCKER + 12 MAJOR + MINOR 批** | `22aeeb81`（35 文件）：prompts 真源 / pev 声明 / T1 同步 / 冒烟实测 | — |
| ② | 15 agent：0 BLOCKER + **1 MAJOR**（R2-F2 MR 描述三处失真）+ 6 MINOR | `4bc28ade`：MR 描述 PATCH（API+Bearer、中文）、静默回退改 fail-loud、T2 防回退守卫、装配断言强化 | **归零 #1**（streak 轮③④⑤） |
| ③ | 11 agent：0 BLOCKER + **1 MAJOR**（R3-F1 自产测试恒真）+ 5 MINOR | `ad7b3a38`：T2 真化（mutation-RED）、minimal() 对齐 null-gate、并发声明收敛等 7 项 | **归零 #2**（streak 轮④⑤⑥） |
| ④ | 0 BLOCKER / 0 MAJOR = 干净 1/3；顺带清账批 R4-1 javadoc 对齐 + 语义锁定测试 | `e7bcfdce` | 后被轮⑤裁定**作废**（见下） |
| ⑤ | **R5-1 假处置识破**：轮④批声称的 javadoc 替换实为静默 no-op（old_string 换行形态与文件不符且无 assert），commit message 描述的改动不在 diff 中——三个独立审查镜头同时捕获。注：同批的语义锁定测试（GateTest +16 行）是真落地的，no-op 的仅 javadoc 两处 | `f01425f0` 真修：带 assert 双验（anchor 匹配 + 替换生效确认），minimal() javadoc 两处对齐 null-gate 真语义 | **归零 #3**：轮④ 1/3 作废、重计 |
| ⑥ | 重计后首个干净轮 **1/3**（双镜头稳定态复扫，零新 commit） | — | streak 1/3 |
| ⑦ | 本轮：稳定态复扫（可复现性验证）+ 本收口报告实例化；四项复现全过（105/0/5 / 冒烟四行 / fork 含 HEAD / 台账零恶化） | —（零新 commit） | **2/3** |

**五次归零教训（轮②③⑤各 1 MAJOR、轮⑧ 2 MAJOR、轮⑨ 1 MAJOR，同一物种）——"字面/声称层面的干净 ≠ 事实干净"**：

1. **R2-F2（轮②）· 对外声称面**：MR 描述与代码事实三处失真（"零改动抽取"实为抽取+处置批、文件数滞后、"Validated by"缺 mock 级限定）。教训：对外叙述必须与 diff 对齐。
2. **R3-F1（轮③）· 测试声称面**：自产防回退测试恒真（mutation 剥掉被守护词元后全套件仍绿）。教训：防回归测试必须 mutation-RED 实证非恒真，"有测试"不等于"有保护"。
3. **R5-1（轮⑤）· 处置声称面**：处置 commit 声称的修改实为静默 no-op，不在 diff 中。教训：处置以 diff 为准；文本替换类操作必须带 assert 验证生效。

三者共同范式：**声称必须可证伪**——MR 文本对 diff、测试对 mutation、处置对 assert 双验。R5-1 之后，"anchor 匹配 + 替换生效"双断言成为处置批惯例。

## §5 诚实边界与 deferred 清单

**结构性边界（诚实标注，不装已解决）**：

- 5 个 skip = env-gated 真 LLM e2e：无 env 不跑、不假装跑过。
- TraceForest 并发边界：仅限单写者/串行宿主循环；**并发派发 deferred**（落地前需 childrenOf 加 monitor 迭代、协作方补原子性；并发风险已定性识别（TraceForest.java:28 串行边界声明、GraphLoopRails.java:34）；量化压测数字（3s/5355 次 CME）来自 4-lens 轮③敌意镜头的实跑探针，证据记录于轮③处置 commit ad7b3a38 的 message——模块内 javadoc 仅含定性声明，量化数字未入库不作可复现宣称）。
- BudgetRail token 池维度 **deferred**：`recordTokens` API 保留待接线（`BudgetRail.java:30/145`，虚构钩子已诚实化）。
- bench 已知副作用（两臂行为一致、非迁移引入）：goal_signal 字段清单的 dotted-path 被模型照抄为顶层键名→扁平 JSON，判分器期望嵌套结构→CA1.1 误形态 GAP（内容本身几乎全对）；修复方向已记录在 59d1a78b。

**收口台账 6 项 MINOR（轮⑦零恶化，不阻塞收口）**：

1. prompt 双源治理（模块自有 prompts 与宿主既有资源的并存治理）
2. e2e 快照更新策略
3. recordTokens 接线 deferred（锚点 `BudgetRail.java:30/145`）
4. bench 语料 `evidence-pool.log` 相关治理（v2 corpus sdx_a3 fixture）
5. 并发派发 deferred（锚点 `TraceForest.java:28`）
6. MR body 数字滞后——轮⑧收口时 PATCH 对齐（轮② PATCH 后又有文件 131→132 增量实发轮②自身 commit 4bc28ade（smoke 基线 log 入库）、测试 104→105）

## §6 用户三令核对与轮⑧终审预告（实际结局：NO-GO 归零 #4，见 §4）——后续轮次预告

| 令 | 内容 | 核对结果 |
|---|---|---|
| 令一 | 连续 3 轮干净才收口（最短路径） | 轮⑥ = 1/3；轮⑦ = 2/3（本轮四项复现全过：105/0/5 全绿、冒烟四行断言度量字段复现一致（elapsed_ms 除外）、fork 含 `f01425f0`、台账零恶化）；轮⑧ = 3/3 终审（实际 NO-GO——见 §4 轮⑧行）|
| 令二 | 诚实计数——处置必须真落地，假处置作废重计 | 轮⑤三镜头裁定轮④ 1/3 作废；`f01425f0` 真修（assert 双验）后重计；轮⑥⑦稳定态、台账六 MINOR 零恶化 |
| 令三 | MR 纪律——对外描述与代码事实一致（中文、随收口 PATCH、数字对齐 HEAD） | R2-F2 已 PATCH 一次（API+Bearer、中文）；遗留"数字滞后"1 项 MINOR 排入轮⑧收口 PATCH，对齐基准以本报告 §1 为准（132 文件 / +11189 / 105 用例） |

**轮⑧终审预告（实际结局：NO-GO 归零 #4，见 §4）——后续轮次预告（3/3 达成时的收口动作）**：

1. MR body 数字 PATCH：文件数 / 行数 / 测试数对齐 `f01425f0`（132 文件、+11189、105/0/5）；
2. 6 项 MINOR 台账移交（转后续批次，不在本 MR 强行清零）；
3. fork 推送态终确认（`fork/feat/loop-forest` = `f01425f0`，当前已含）；
4. 本报告随 MR 归档至 `docs/`。
