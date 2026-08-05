# traffic-forecast-qa — 流量预测智能问数 Skill

> 状态：**设计阶段（README 草案）**，待评审确认后再生成代码。

## 1. 概述

`traffic-forecast-qa` 是面向公路 AI 场景的精品 Skill，允许用户以**自然语言提问**，由 Agent 结合 [`db-connector`](../../tools/db-connector/) 工具查询历史流量数据，并完成**流量预测与趋势分析**。

典型问答示例：

- 「预测 S001 收费站明天早高峰（7-9 点）的流量」
- 「G4 京港澳高速 K1200 路段下周日均流量趋势」
- 「对比去年同期，今年国庆 G15 流量变化」

该 Skill 不直接访问数据库，所有数据查询统一经由 `db-connector`，复用其安全、审计与权限能力。

## 2. 适用场景

| 场景 | 说明 |
|------|------|
| 收费站流量预测 | 按收费站、时段预测通行流量，辅助 staffing 与车道开闭 |
| 路段流量预测 | 按路段、方向预测断面流量，辅助诱导与管控 |
| 节假日流量预估 | 结合节假日类型预测峰值与高峰窗口 |
| 同比/环比分析 | 对比历史同期，识别异常增长或回落 |
| 异常预警 | 实际值显著偏离预测值时触发提示 |

## 3. 能力边界

**能做：**

- 自然语言意图识别与参数抽取（站点/路段、时间范围、预测窗口、粒度）
- 通过 `db-connector` 执行参数化查询获取历史数据
- 基于时序统计方法完成短期预测（移动平均 / 同比环比 / 指数平滑）
- 输出结构化预测结果（点估计 + 置信区间）与可视化建议
- 多轮澄清对话（参数缺失时主动追问）

**不做：**

- 不绕过 `db-connector` 直接访问数据库
- 不执行写操作（仅消费 `readonly` 模式的查询能力）
- 不替代专业交通仿真系统（宏观 OD 分配等）
- 首版不引入重型 ML 训练框架，预测以轻量统计方法为主

## 4. 工作流

```
用户自然语言提问
      │
      ▼
① 意图识别 ── 非流量问数意图 ──▶ 礼貌拒绝/引导
      │（流量预测/分析）
      ▼
② 参数抽取
   ├─ 目标对象：站点 ID / 路段编码
   ├─ 时间范围：历史窗口（用于训练/统计）
   ├─ 预测窗口：未来时段
   └─ 粒度：小时 / 日 / 周
      │
      ▼
③ 参数校验 ── 缺失/歧义 ──▶ 多轮澄清追问
      │
      ▼
④ SQL 生成（参数化模板）
      │
      ▼
⑤ 调用 db-connector.query()  ──▶ 历史数据
      │
      ▼
⑥ 预测计算
   ├─ 基础：移动平均 / 同比环比
   └─ 增强：指数平滑 / Holt-Winters（可选）
      │
      ▼
⑦ 结果组织
   ├─ 点估计 + 置信区间
   ├─ 趋势描述（自然语言）
   └─ 可视化建议（图表类型 + 数据序列）
      │
      ▼
   返回结构化结果
```

## 5. 与 db-connector 的关系

| 维度 | 约定 |
|------|------|
| 调用方向 | 本 Skill → `db-connector`（单向依赖） |
| 数据访问 | 仅调用 `query()`，不调用 `insert/update/delete` |
| 模式约束 | 要求 `db-connector` 以 `mode: readonly` 运行 |
| 表范围 | 仅访问 `db-connector` 中 `allowed-tables` 授权的流量相关表（如 `traffic_flow`、`toll_station`、`road_segment`） |
| SQL 安全 | 本 Skill 生成**参数化模板**交由 `db-connector` 执行，不自行拼接 SQL；标识符经白名单校验 |
| 审计 | 所有查询经 `db-connector` 审计日志记录，本 Skill 额外记录「问答上下文 → 查询」映射 |

## 6. 数据假设（需与客户对齐）

本 Skill 假设目标数据库中存在流量相关表，结构示例（可适配）：

```sql
-- 收费站/路段流量明细（示例）
CREATE TABLE traffic_flow (
    id           BIGINT PRIMARY KEY,
    station_id   VARCHAR(32)  NOT NULL,   -- 站点/路段编码
    road_code    VARCHAR(16),             -- 道路编码（如 G4/G15）
    ts           TIMESTAMP    NOT NULL,   -- 统计时刻
    granularity  VARCHAR(8)   NOT NULL,   -- HOUR / DAY
    direction    VARCHAR(4),              -- 上行/下行
    flow         INT          NOT NULL    -- 流量（辆）
);
```

> 实际表结构以客户部署为准，Skill 在加载时读取「数据口径配置」做字段映射，避免硬编码。

## 7. 预测方法（分层设计）

| 层级 | 方法 | 适用 | 说明 |
|------|------|------|------|
| L1 基础 | 移动平均 / 同比环比 | 短期、数据平稳 | 无外部依赖，首版交付 |
| L2 增强 | 指数平滑 / Holt-Winters | 含趋势与季节性 | 首版交付（statsmodels） |
| L3 高级 | ARIMA / Prophet / 时序模型 | 复杂场景 | 后续版本，按需引入 |

首版交付 L1 + L2，保证可用与可解释；L3 作为路线图。

## 8. 输入输出契约（设计草案）

### 8.1 输入

```json
{
  "question": "预测 S001 收费站明天早高峰流量",
  "context": {
    "history": []   // 多轮对话历史（可选）
  }
}
```

### 8.2 输出

```json
{
  "status": "ok",
  "understood": {
    "target": {"type": "station", "id": "S001"},
    "historicalWindow": "P30D",
    "forecastWindow": "2026-07-31T07:00~09:00",
    "granularity": "HOUR"
  },
  "queries": [
    {"template": "SELECT ts, flow FROM traffic_flow WHERE station_id=? AND ts BETWEEN ? AND ? ORDER BY ts", "auditIds": ["aud-..."]}
  ],
  "forecast": {
    "method": "holt_winters",
    "points": [
      {"ts": "2026-07-31T07:00:00", "value": 1234, "lower": 1100, "upper": 1380},
      {"ts": "2026-07-31T08:00:00", "value": 1450, "lower": 1300, "upper": 1600}
    ]
  },
  "summary": "预计明日早高峰 S001 站 7-9 点流量约 2684 辆，同比上周增长约 5%。",
  "visualization": {"type": "line", "series": [...]}
}
```

## 9. 目录结构（规划）

```
traffic-forecast-qa/
├── README.md                       # 本设计文档
├── SKILL.md                        # Skill 主文件（指令、触发条件、工作流）— 代码阶段产出
├── config/
│   └── data-mapping.yml            # 数据口径与字段映射配置
├── src/main/java/com/openjiuwen/skills/trafficforecastqa/
│   ├── TrafficForecastQaSkill.java       # Skill 入口
│   ├── intent/
│   │   ├── IntentRecognizer.java         # 意图识别
│   │   └── ParamExtractor.java           # 参数抽取
│   ├── query/
│   │   ├── SqlTemplateBuilder.java       # 参数化 SQL 模板生成（标识符白名单）
│   │   └── DataFetcher.java              # 调用 db-connector
│   ├── forecast/
│   │   ├── ForecastEngine.java           # 预测引擎接口
│   │   ├── MovingAverageForecaster.java  # L1
│   │   └── HoltWintersForecaster.java    # L2
│   ├── clarify/
│   │   └── ClarifyDialog.java            # 多轮澄清
│   └── response/
│       └── ResultAssembler.java          # 结果组织
└── src/test/java/...
```

## 10. 安全与合规要点

- **SQL 安全**：仅生成参数化模板，标识符（表名/列名）取自配置白名单，不接受用户输入的原始表名/列名
- **只读约束**：Skill 层声明 `requiresReadonly: true`，校验 `db-connector` 模式，否则拒绝运行
- **数据脱敏**：返回结果中不包含个人隐私字段；如遇敏感字段按 methodology 规范脱敏
- **可解释性**：预测结果附带 `method` 与置信区间，避免黑盒输出

## 11. 技术栈与依赖（Python）

- Python 3.10+
- `db-connector`（同仓 workshop/tools/db-connector，数据访问底座）
- Pydantic v2（数据模型与配置校验）
- pandas / numpy（时序数据处理与统计计算）
- PyYAML（数据口径配置加载）
- （L2 可选）statsmodels（Holt-Winters 指数平滑实现）
- pytest（测试）

## 12. 设计决策（已确认）

| 项 | 决策 |
|----|------|
| 流量表结构 | 客户已有表，通过 `db-connector.import_schema()` 反射导入并做字段映射 |
| 预测粒度 | 首版支持**1 小时**粒度 |
| 预测方法 | 首版交付 L1（移动平均/同比环比）+ L2（指数平滑/Holt-Winters） |
| 多轮澄清 | 首版必须支持，参数缺失/歧义时主动追问 |
| 可视化 | 返回数据序列 + 图表类型建议，不绑定具体图表组件 |

## 13. 贡献说明

遵循仓库 [贡献指南](../../../../CONTRIBUTING.md) 提交 PR。
