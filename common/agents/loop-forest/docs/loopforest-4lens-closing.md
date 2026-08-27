# loop-forest 四镜头对抗审查收口报告

> 本报告是轮⑤ closing-prep 六节骨架的实例化（轮⑦产出）。数据全部取自 HEAD `f01425f0` 的可复现证据（git diff / surefire / smoke 复跑），未引用任何不可复核的口头数字。

- **审查对象**：`common/agents/loop-forest`（分支 `feat/loop-forest`）
- **报告创建时 HEAD**：`f01425f0`（后续轮次处置见 §4 台账；当前分支 HEAD 以 `git log -1` 为准）
- **对照基线**：`70ffe929`（!381 合入点）——七 commit 链，132 个文件**全部**位于本模块目录，+11189 行、0 删除（本报告自身 commit 后为 133 文件/+11309）（纯新增，零修改任何既有模块）
- **轮次角色**：各轮进度、归零计数与结局见 §4（单一真源——本行不再并行维护轮次进度）
- **报告日期**：2026-08-27

---

## §1 终态基线

| 维度 | 终态 | 证据 |
|---|---|---|
| 测试 | **105 run / 0 failures / 0 errors / 5 skipped**，BUILD SUCCESS | 轮⑦全量复跑 `mvn test`（surefire 汇总：`Tests run: 105, Failures: 0, Errors: 0, Skipped: 5`） |
| 轮次台账 | 全量见 §4（单一真源——本表不再并行维护轮次计数） |
| skip 语义 | 5 个 skip = 5 个 env-gated 真 LLM e2e（DeepResearch / VetoSchema / V2Bench / MinimalWrite / AgentSmoke 各 1 例）——诚实边界而非缺陷 | surefire 逐类明细：20 个测试类中 5 个 e2e 类各 1 例 skip |
| 冒烟 | 四行断言度量字段**两次复现一致**（elapsed_ms 为时延观测非断言值，两次运行存在差异）（入库基线 20260827-122036 vs 轮⑦复跑 20260827-231008） | `docs/smoke-baseline-20260827.log` + `logs/smoke-20260827-231008.log` |
| 变更面 | 7 commit / 132 文件 / +11189 / −0，全部位于 `common/agents/loop-forest/` | `git diff --shortstat 70ffe929..f01425f0` |
| 处置台账 | 累计处置项数与归零计数以 §4 台账为准（轮①-⑦ 期 35 项 + 轮⑧⑨⑩ 增量）；余 6 项 MINOR 全部转收口台账，轮⑦零恶化 | 见 §4 / §5 |
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

## §4 轮次台账（单一真源：其余制品一律指向本表）

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
| 轮⑧ | 终审 NO-GO：**2 MAJOR**（MR body 数字半对齐 + 报告口头数字'5355 记录于 javadoc'）+ 4 MINOR → 归零 #4；勘误批 a4f32329 + MR body v3-v5 |
| 轮⑨ | 勘误验证：**1 MAJOR**（MR body 轮次台账少计轮⑧ MAJOR 数）+ 1 MINOR → 归零 #5；报告补行 39a411d3+aaf18bcf + MR PATCH |
| 轮⑩ | 声称面终闸：**3 MAJOR**（§4 行落点错位 / MR body 再少计轮⑨ / MR title'五层'零锚）+ 4 MINOR → 归零 #6；单一真源化处置（本 commit） |
| 轮⑪ | 冻结验证+移交：**1 MAJOR**（R10 commit 声称'§6 基准改口径'不在 diff——R5-1 物种复发）+ 2 MINOR | §6 三处真修+头部注记+移交清单（bb48bd79/3057af1e） | **归零 #7**：streak=0，轮⑫起重计 |
| 轮⑫ | 增量终验：**4 MAJOR**（§5.2 B2 被处置 commit 打红 / 教训 7-6 失配 / 轮⑪双行 streak 矛盾 / commit message 14≠13 假计数）+ 1 MINOR | B2 锚去数字化+教训补 7/8+双行 reconcile+口径注记（本 commit） | **归零 #8**：streak=0，轮⑬起重计 |
| 轮⑬ | 三门+增量：**2 MAJOR**（B2 pass 回显假计数 6/6 / squash bullet 同行半清扫）+ 1 MINOR | B2 pass 插值化+全散文计数面 count-free（本 commit） | **归零 #9**：streak=0，轮⑭起重计 |
| 轮⑭ | 增量+三门：**干净**（0 BLOCKER / 0 MAJOR / 2 MINOR 入台账） | 无需处置；两 MINOR 顺手清（本 commit：B1 扩展+L89 渲染） | streak=**1/3**，轮⑮⑯续 |
| 轮⑪ | R11-移交终镜：移交清单落库（§5.1 表 A 六 MINOR 锚点+验收判据、表 B R10 三移交项）+ 终检脚本（§5.2）；§4 冻结态核验通过（10 个被引 commit 锚全存在、变更面 133 文件/0 删除实测复认、归零 #1-#6 计数一致）；遗留"轮次角色"行改 §4 指针收尾单一真源化。零新文件（133 保持，MR body 零 PATCH 需求） | （本 commit） | （自评已被同轮轮⑪ MAJOR 归零 #7 取代，streak=0——R12-F3 处置注记） |

**归零教训（各轮计数见 §4 台账；同物种：声称面与证据面错位）注：以下编号清单每归零一条——"字面/声称层面的干净 ≠ 事实干净"**：

1. **R2-F2（轮②）· 对外声称面**：MR 描述与代码事实三处失真（"零改动抽取"实为抽取+处置批、文件数滞后、"Validated by"缺 mock 级限定）。教训：对外叙述必须与 diff 对齐。
2. **R3-F1（轮③）· 测试声称面**：自产防回退测试恒真（mutation 剥掉被守护词元后全套件仍绿）。教训：防回归测试必须 mutation-RED 实证非恒真，"有测试"不等于"有保护"。
3. **R5-1（轮⑤）· 处置声称面**：处置 commit 声称的修改实为静默 no-op，不在 diff 中。教训：处置以 diff 为准；文本替换类操作必须带 assert 验证生效。
4. 轮⑧：MR body 半对齐 + 报告引用不存在的锚——对外描述须逐条对照 git diff
5. 轮⑨：轮次台账转写少计——多份台账手抄同步必滞后，须单一真源化
6. 轮⑩：title'五层'零锚 + §4 行落点错位——最外层声称面（title/落点）同样要审
7. 轮⑪：处置 commit 的 message bullet 也是声称面——'§6 基准改口径'不在 diff 中；commit 声称与 diff 逐条对照
8. 轮⑫：commit message 的断言计数（14 项/14-14 PASS）生而不真（实体 13 项，D6 重复计）——数字以脚本实体自数为准，不转抄声称
9. 轮⑬：pass 回显与散见计数（标题号/轮次跨度/squash 模板）都是声称面——治本=全部 count-free 化（动态插值或纯指针），不留任何手维护数字

诸教训共同范式：**声称必须可证伪**——MR 文本对 diff、测试对 mutation、处置对 assert 双验。R5-1 之后，"anchor 匹配 + 替换生效"双断言成为处置批惯例。

## §5 诚实边界与 deferred 清单

**结构性边界（诚实标注，不装已解决）**：

- 5 个 skip = env-gated 真 LLM e2e：无 env 不跑、不假装跑过。
- TraceForest 并发边界：仅限单写者/串行宿主循环；**并发派发 deferred**（落地前需 childrenOf 加 monitor 迭代、协作方补原子性；并发风险已定性识别（TraceForest.java:28 串行边界声明、GraphLoopRails.java:34）；量化压测数字（3s/5355 次 CME）来自 4-lens 轮③敌意镜头的实跑探针，证据记录于轮③处置 commit ad7b3a38 的 message——模块内 javadoc 仅含定性声明，量化数字未入库不作可复现宣称）。
- BudgetRail token 池维度 **deferred**：`recordTokens` API 保留待接线（`BudgetRail.java:30/145`，虚构钩子已诚实化）。
- bench 已知副作用（两臂行为一致、非迁移引入）：goal_signal 字段清单的 dotted-path 被模型照抄为顶层键名→扁平 JSON，判分器期望嵌套结构→CA1.1 误形态 GAP（内容本身几乎全对）；修复方向已记录在 59d1a78b。

**收口台账 6 项 MINOR（轮⑦零恶化，不阻塞收口；锚点 / 验收判据 / 移交细化见 §5.1——移交真源）**：

1. prompt 双源治理（模块自有 prompts 与宿主既有资源的并存治理）
2. e2e 快照更新策略
3. recordTokens 接线 deferred（锚点 `BudgetRail.java:30/145`）
4. bench 语料 `evidence-pool.log` 相关治理（v2 corpus sdx_a3 fixture）
5. 并发派发 deferred（锚点 `TraceForest.java:28`）
6. MR body 数字滞后——轮⑧收口时 PATCH 对齐（轮② PATCH 后又有文件 131→132 增量实发轮②自身 commit 4bc28ade（smoke 基线 log 入库）、测试 104→105）

### §5.1 移交清单（轮⑪ 落库——第二批处置的唯一输入）

**落点决策**：不新增 `docs/handover.md`，本附录即移交真源。理由：(a) R10 结构性诊断"多份台账手抄同步必滞后"刚治本，新增独立文件即第六份并行台账（同物种复发）；(b) 保持 MR 文件数 133 不变——MR body "133 文件"表述继续成立，本轮零 MR PATCH 动作（少一次对齐 = 少一次失真面，轮⑧⑨⑩ 三次归零全是这个物种）；(c) 本报告已定随 MR 归档至 `docs/`，移交内容随报告走即随 MR 走。

**表 A：六项 MINOR 移交（编号承上文 1-6；锚点为 HEAD `5b70621c` 实测行号，路径相对本模块根）**

| # | 项 | deferred 锚（file:line） | 验收判据（可证伪） |
|---|---|---|---|
| 1 | prompt 双源治理 | `src/main/resources/prompts/`（18 个 .txt）；加载真源 `src/main/java/com/openjiuwen/agents/loopforest/search/PromptTemplates.java:20`、`src/main/java/com/openjiuwen/agents/loopforest/LoopForestAgent.java:103` | 模块 / 宿主资源归属边界成文（哪些 key 归模块 prompts/、哪些归宿主既有资源）；任一模板增改后 `mvn test` 仍绿且冒烟 `prompts_source=module_owned` 保持 true |
| 2 | e2e 快照更新策略 | `docs/smoke-baseline-20260827.log`（入库基线）；数值断言 `src/test/java/com/openjiuwen/agents/loopforest/smoke/SmokeMetricsTest.java:94/158/179` | 策略成文：断言字段允许变更的触发条件；变更时基线 log 与 SmokeMetricsTest 断言必须同批 commit、旧基线保留（不静默改数） |
| 3 | recordTokens 接线 | `src/main/java/com/openjiuwen/agents/loopforest/rail/BudgetRail.java:30`（deferred 注记）/ `:145`（API 保留待接线） | token 池拒绝路径有单测（超池拒 / 未超放行）且 mutation-RED 实证非恒真；`:30` 注记同批移除——javadoc 与实现不再错位 |
| 4 | bench corpus 治理 | `src/test/resources/bench/v2/sealed/answers.json:43`（sdx_a3/evidence-pool.log sha256 pin）；注意 `src/test/resources/bench/v2/corpus/sdx_a3/` 下该文件**缺席**（仅 incident.md / postmortem-draft.md）——v2a3 任务当前不可跑，e2e 实跑 arm_a5 | 去留决策成文：补 evidence-pool.log 入 corpus，或 contract 明示 v2a3 sealed-only；决策后 answers pin 与 corpus 实际文件一致 |
| 5 | 并发派发 | `src/main/java/com/openjiuwen/agents/loopforest/observability/TraceForest.java:28`（串行边界声明）；`src/main/java/com/openjiuwen/agents/loopforest/rail/GraphLoopRails.java:34-35` | 落地时 childrenOf 加 monitor 迭代 + 分支添加原子化 + 多写者并发压测绿；未落地则两处声明保持（锚不删） |
| 6 | MR body 数字 | MR !385 body "133 文件"（轮次 / 归零 / 项数已 R10 count-free 指向 §4） | squash / 收口时 `git diff --shortstat 70ffe929..HEAD` 实测逐项对齐 body 数字；本 MR 后续不加新文件则 133 恒成立；行数不引用本报告数字，一律实测 |

**表 B：R10 移交三项（流程面，非代码）**

1. **squash message 预案**（squash 合入时用；其中数字一律届时不从本报告转抄、以 git 实测为准）：

   ```
   loop-forest：长程任务 Agent 的外置纪律与结构（三门 rail + 轨迹森林）

   - 三门（GraphLoopRails.registerOnto 单一装配真源，config 可开关）：VetoRail 写入契约（产物零提及被拒事实即拒写）/ BudgetRail 三维预算 / ConvergenceRail 跨分支收敛（归因不替换）
   - 结构层：TraceForest 轨迹树寻址与回滚路径 + ForkOrchestrator 分叉登记（组合 edpa SubAgentExecutor SPI）；统一入口 LoopForestAgent
   - 133 文件全部位于 common/agents/loop-forest/，纯新增、零修改既有模块（行数以 squash 时 git diff --shortstat 70ffe929..squash 点实测为准）
   - 测试 105 run / 0 failures / 0 errors / 5 skipped（5 = env-gated 真 LLM e2e，诚实边界非缺陷）；冒烟 A 档零 env 可复跑：bash common/agents/loop-forest/smoke.sh --a-only
   - prompts 模块自有（src/main/resources/prompts/，prompts_source=module_owned 断言入库防寄生）
   - 对抗收口轮次与归零计数见 docs/loopforest-4lens-closing.md §4（单一真源）
   ```
2. **ci-failed 沟通口径**：CI 红先分诊再发言——(a) 缺 env 的 e2e：本地形态是 **skip 不是 fail**，CI 若因缺 `DEEPSEEK_API_KEY`/`BASE_URL` 等报 fail 即 runner 配置问题，非代码缺陷；(b) 编译 / 单测真红：以 surefire 汇总行为准贴回 MR。对外一句话口径："A 档零 env 可复跑（`bash common/agents/loop-forest/smoke.sh --a-only`，rc=0）；B 档需 DEEPSEEK_API_KEY/BASE_URL；5 个 skip 是诚实边界设计，不是没跑到。"
3. **治理第二批 MR 建议**：6 MINOR 不在本 MR 强行清零（§6 收口动作 2 既有承诺），合入后开第二批——批 1 纯治理文档（项 1/2/4：双源边界、快照策略、corpus 去留，零承重代码）；批 2 小接线（项 3 recordTokens：改 BudgetRail 承重路径，走 mock 单测 + mutation-RED gate + javadoc 同批）；批 3 单列最大项（项 5 并发派发：TraceForest 结构改动 + 并发压测，独立成 MR）。每批独立可回退，验收判据照表 A 逐项。

### §5.2 轮⑫⑬ 终检脚本（逐项断言；未来 CI 种子）

> **口径注记（R12-F4 处置）**：断言项=**13**（A1-A3/B1-B2/C1-C2/D1-D6）；commit 3057af1e/bb48bd79 message 中"14 项/14-14"为口径笔误（D6 重复计）——以本脚本实体自数为准。B1 枚举/计数随每次 §4 增行的处置批同步扩展（R14-F1 治本——干净轮行无归零词元，B1 是其唯一行数守卫）。

提取运行（仓库根）：`sed -n '/^```bash/,/^```$/p' common/agents/loop-forest/docs/loopforest-4lens-closing.md | sed '1d;$d' | bash`——默认零 env 零网络；`STRICT=1` 时 D6 fork 检查由 WARN 升 FAIL（收口终态用）。任一 FAIL 即非零退出。数字口径 = 轮⑪ 落库时 HEAD；第二批 MR 落地时同批更新断言（表 A 项 2 快照策略）。

```bash
#!/bin/bash
# loop-forest 收口终检——轮⑫⑬逐项断言（未来 CI 种子；A 档零 env 零网络）
set -u
BASE=70ffe929; MOD=common/agents/loop-forest
REP=$MOD/docs/loopforest-4lens-closing.md
SRC=$MOD/src/main/java/com/openjiuwen/agents/loopforest
cd "$(git rev-parse --show-toplevel)" || exit 2
fails=0; warns=0
pass(){ echo "PASS $1"; }; fail(){ echo "FAIL $1"; fails=$((fails+1)); }; warn(){ echo "WARN $1"; warns=$((warns+1)); }

# A 变更面（§1）
n=$(git diff --name-only $BASE..HEAD | wc -l | tr -d ' ')
[ "$n" = 133 ] && pass "A1 文件数=133（零新文件）" || fail "A1 文件数=${n}≠133（新增文件需 MR body 同步）"
[ -z "$(git diff --name-only $BASE..HEAD | grep -v "^$MOD/")" ] && pass "A2 变更全在模块内" || fail "A2 存在越界文件"
[ -z "$(git diff --numstat $BASE..HEAD | awk '$2>0')" ] && pass "A3 零删除" || fail "A3 存在删除行"

# B 台账真源（§4）
rows=0; for r in ① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭; do grep -qE "^\| (轮)?$r \|" $REP && rows=$((rows+1)); done
[ "$rows" -eq 14 ] && pass "B1 §4 轮①-⑭行齐（rows=$rows/14）" || fail "B1 §4 行=$rows/14（少计=轮⑨⑩物种——干净轮行无归零词元，B1 是其唯一行数守卫）"
z=$(grep -oE '归零 #[0-9]+' $REP | sort -u | wc -l | tr -d ' ')
l=$(sed -n '/^\*\*.*归零教训/,/^.*共同范式/p' $REP | grep -cE '^[0-9]+\. ')
[ "$z" = "$l" ] && [ "$z" -ge 8 ] && pass "B2 归零事件=${z} 且教训列表=${l}" || fail "B2 归零=${z} 教训=${l}（不一致）"

# C 测试与冒烟（A 档零 env）
if mvn -f $MOD/pom.xml test >/dev/null 2>&1; then
  sum=$(awk -F'[:,]' '/^Tests run:/{t+=$2;f+=$4;e+=$6;s+=$8} END{printf "Tests run: %d, Failures: %d, Errors: %d, Skipped: %d",t,f,e,s}' $MOD/target/surefire-reports/*.txt)
  [ "$sum" = "Tests run: 105, Failures: 0, Errors: 0, Skipped: 5" ] && pass "C1 105/0/0/5" || fail "C1 surefire 汇总=$sum"
else fail "C1 mvn test 非 BUILD SUCCESS"; fi
lg=""; bash $MOD/smoke.sh --a-only >/dev/null 2>&1 && lg=$(ls -t $MOD/logs/smoke-*.log | head -1)
[ -n "$lg" ] && grep -q 'A 档 rc=0' "$lg" && grep -q 'prompts_source=module_owned' "$lg" \
  && pass "C2 冒烟 A 档 rc=0 + module_owned" || fail "C2 冒烟 A 档失败"

# D 六 MINOR deferred 锚存活（§5.1 表 A——锚漂移即移交清单失真）
[ "$(ls $MOD/src/main/resources/prompts/*.txt | wc -l | tr -d ' ')" = 18 ] && pass "D1 prompts=18 个（项1）" || fail "D1 prompts 数漂移（项1）"
[ -f $MOD/docs/smoke-baseline-20260827.log ] && pass "D2 基线 log 在（项2）" || fail "D2 基线 log 缺（项2）"
grep -q deferred <(sed -n 30p $SRC/rail/BudgetRail.java) && grep -q recordTokens <(sed -n 145p $SRC/rail/BudgetRail.java) \
  && pass "D3 BudgetRail:30/145 锚在（项3）" || fail "D3 BudgetRail 锚漂移（项3）"
grep -q 'sdx_a3/evidence-pool.log' $MOD/src/test/resources/bench/v2/sealed/answers.json \
  && pass "D4 answers sha pin 在（项4）" || fail "D4 answers pin 缺（项4）"
grep -q 并发边界 <(sed -n 28p $SRC/observability/TraceForest.java) && grep -q 串行 <(sed -n 34p $SRC/rail/GraphLoopRails.java) \
  && pass "D5 TraceForest:28 + Rails:34 锚在（项5）" || fail "D5 并发锚漂移（项5）"
if git branch -r --contains HEAD | grep -q 'fork/feat/loop-forest'; then pass "D6 fork 含 HEAD（项6）"
else if [ "${STRICT:-0}" = 1 ]; then fail "D6 fork 未含 HEAD（收口态必须先 push）"; else warn "D6 fork 未含 HEAD（push 后复跑；收口终态用 STRICT=1）"; fi; fi

echo "----"; echo "FAILS=$fails WARNS=$warns"; [ $fails -eq 0 ]
```

## §6 用户三令核对与轮⑧终审预告（实际结局：NO-GO 归零 #4，见 §4）——后续轮次预告

| 令 | 内容 | 核对结果 |
|---|---|---|
| 令一 | 连续 3 轮干净才收口（最短路径） | 轮⑥ = 1/3；轮⑦ = 2/3（本轮四项复现全过：105/0/5 全绿、冒烟四行断言度量字段复现一致（elapsed_ms 除外）、fork 含 `f01425f0`、台账零恶化）；轮⑧ = 3/3 终审（实际 NO-GO——见 §4 轮⑧行）|
| 令二 | 诚实计数——处置必须真落地，假处置作废重计 | 轮⑤三镜头裁定轮④ 1/3 作废；`f01425f0` 真修（assert 双验）后重计；轮⑥⑦稳定态、台账六 MINOR 零恶化 |
| 令三 | MR 纪律——对外描述与代码事实一致（中文、随收口 PATCH、数字对齐 HEAD） | R2-F2 已 PATCH 一次（API+Bearer、中文）；对齐基准：以当前 HEAD 的 git diff --shortstat 实测为准（133 文件计数稳定，行数随勘误 commit 微增——R10 起对 MR body 已 count-free，本条为历史注记） |

**轮⑧终审预告（实际结局：NO-GO 归零 #4，见 §4）——后续轮次预告（3/3 达成时的收口动作）**：

1. MR body 终态已 count-free（R10 起——轮次/计数全指向本报告 §4，不再需要数字 PATCH；此项为历史注记）；
2. 6 项 MINOR 台账移交（转后续批次，不在本 MR 强行清零）；
3. fork 推送态终确认：fork/feat/loop-forest 含当前 HEAD（git ls-remote 实测；不再锚定具体 SHA）；
4. 本报告随 MR 归档至 `docs/`。
