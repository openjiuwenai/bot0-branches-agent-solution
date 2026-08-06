# traffic-flow-forecast-control — 公路流量预测 + 智能管控 Agent 解决方案

## 1. 概述

本解决方案面向 **公路运输场景**，构建一个「流量预测 + 智能管控」智能体，实现从历史数据查询、流量预测到管控建议生成的端到端闭环。

智能体通过自然语言与用户交互，能够：

- **预测**：基于历史车流数据，预测未来时段流量（小时粒度）
- **分析**：识别流量趋势、同比环比变化、异常波动
- **管控**：当预测流量接近或超过阈值时，生成智能管控建议（如限流、分流引导、开放应急车道等）

本解决方案复用顶层 `workshop/` 中的共享资产，不重复造轮子：

| 资产 | 位置 | 角色 |
|------|------|------|
| db-connector | [workshop/tools/db-connector/](../../workshop/tools/db-connector/) | 数据访问底座 |
| traffic-forecast-qa | [workshop/skills/traffic-forecast-qa/](../../workshop/skills/traffic-forecast-qa/) | 流量预测 Skill |
| db-connector-server | [workshop/mcp/db-connector-server/](../../workshop/mcp/db-connector-server/) | MCP 服务暴露 |

## 2. 解决方案架构

```
┌─────────────────────────────────────────────────────────┐
│                    用户（自然语言）                        │
│  "预测某高速明天早高峰某收费站车流，超限给出管控建议"        │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              流量预测 + 智能管控 Agent                     │
│                                                         │
│  ┌─────────┐   ┌───────────┐   ┌──────────┐   ┌──────┐ │
│  │ 意图识别 │──▶│ 流量预测   │──▶│ 管控决策  │──▶│ 响应 │ │
│  │         │   │ Skill     │   │ 模块      │   │ 组织 │ │
│  └─────────┘   └─────┬─────┘   └──────────┘   └──────┘ │
│                      │                                  │
│                      ▼                                  │
│              ┌───────────────┐                          │
│              │ db-connector  │  (MCP / 直接调用)         │
│              │  Tool         │                          │
│              └───────┬───────┘                          │
│                      │                                  │
└──────────────────────┼──────────────────────────────────┘
                       │
                       ▼
              ┌─────────────────┐
              │  公路业务数据库   │
              │  (MySQL/PG)     │
              │  - 车流明细      │
              │  - 收费站信息    │
              │  - 路段容量      │
              │  - 限速/施工     │
              └─────────────────┘
```

## 3. 资产使用方式

### 3.1 db-connector 工具（数据访问底座）

**作用**：安全连接公路业务数据库，提供参数化查询能力。

**部署配置**：

```yaml
# config.yaml — 某高速路段配置
openjiuwen:
  tools:
    db-connector:
      mode: readonly              # 预测场景只需只读
      data-source:
        type: mysql
        host: env:DB_HOST
        port: 3306
        database: env:DB_NAME     # 高速业务库
        username: env:DB_USER
        password: env:DB_PASSWORD
      schema-import:
        enabled: true
        allowed-tables:
          - highway_traffic_flow        # 某高速车流明细表
          - highway_toll_station        # 某高速收费站信息表
          - road_segment_capacity       # 路段容量表
          - speed_restriction           # 限速信息表
        sensitive-columns: ["plate_no", "phone"]
      security:
        allowed-tables:
          - highway_traffic_flow
          - highway_toll_station
          - road_segment_capacity
          - speed_restriction
        max-rows: 50000             # 预测需较多历史数据
        query-timeout-ms: 60000
```

**表结构导入**：

```python
from openjiuwen.tools.db_connector import load_config, DefaultDbConnectorTool

config = load_config("config.yaml")
tool = DefaultDbConnectorTool(config)

# 导入已有表结构（客户已有表，无需手动建表）
snapshot = tool.import_schema()
# → 生成 schema-snapshot.json，供 Skill 做字段映射
```

### 3.2 traffic-forecast-qa Skill（流量预测）

**作用**：自然语言 → 意图识别 → 参数抽取 → 数据查询 → 流量预测。

**字段映射配置**（适配某高速路段表结构）：

```yaml
# data-mapping.yml
traffic-forecast-qa:
  flow-table:
    name: highway_traffic_flow
    columns:
      station-id: station_code       # 收费站编码（如 station_001）
      road-code: road_code           # 道路编码（某高速=GXXXX）
      timestamp: stat_time           # 统计时刻
      granularity: granularity       # 粒度
      direction: direction           # 上行/下行（A 方向/B 方向）
      flow: vehicle_count            # 车流量
  forecast:
    granularity: HOUR
    default-history-window-days: 30
    methods:
      - moving_average
      - year_over_year
      - holt_winters
  clarify:
    enabled: true
    max-rounds: 3
    required-params:
      - target
      - forecast-window
```

**调用方式**：

```python
from openjiuwen.skills.traffic_forecast_qa import TrafficForecastQaSkill

# db_connector 为上面初始化的 DefaultDbConnectorTool 实例
skill = TrafficForecastQaSkill(tool, "data-mapping.yml")

# 自然语言提问
result = skill.ask("预测某高速某收费站明天早高峰车流")
# → 返回预测结果（点估计 + 置信区间 + 趋势摘要）
```

### 3.3 db-connector-server MCP（服务暴露）

**作用**：将 db-connector 暴露为 MCP 服务，供外部 Agent 运行时调用。

**启动**：

```bash
cd workshop
./start.sh
# 或指定传输方式
MCP_TRANSPORT=sse MCP_PORT=8080 ./start.sh
```

**Agent 运行时对接**：Agent 平台通过 MCP 协议调用 `query` / `import_schema` 等工具，无需直接 import Python 包。

## 4. 工作流详解

### 4.1 整体工作流

```
用户提问
  │
  ▼
① 意图识别 ─────────────────────────────────────────────────
  │  判断是否为「流量预测 + 管控」意图
  │  ├─ 是 → 继续
  │  └─ 否 → 礼貌引导至流量相关话题
  │
  ▼
② 参数抽取 + 多轮澄清 ──────────────────────────────────────
  │  抽取：目标道路/收费站、预测时间窗口、方向
  │  ├─ 参数完整 → 继续
  │  └─ 参数缺失 → 追问（最多 3 轮）
  │     例："请问您要预测哪个收费站？如 A 收费站、B 收费站"
  │
  ▼
③ 数据查询（db-connector）─────────────────────────────────
  │  生成参数化 SQL，查询历史车流数据
  │  SELECT stat_time, vehicle_count
  │  FROM highway_traffic_flow
  │  WHERE station_code = %s
  │    AND stat_time BETWEEN %s AND %s
  │    AND granularity = %s
  │  ORDER BY stat_time
  │  → 返回 30 天小时级历史数据
  │
  ▼
④ 流量预测（traffic-forecast-qa Skill）────────────────────
  │  数据量 ≥ 48 条 → Holt-Winters 指数平滑（L2）
  │  数据量 < 48 条 → 移动平均（L1）
  │  → 输出：未来时段点估计 + 置信区间
  │
  ▼
⑤ 管控阈值判定 ────────────────────────────────────────────
  │  查询路段容量表（road_segment_capacity）
  │  比对预测值与容量阈值：
  │  ├─ 预测值 < 容量 80%  → 绿色：正常运行
  │  ├─ 80% ≤ 预测值 < 100% → 黄色：预警，建议关注
  │  ├─ 预测值 ≥ 容量 100% → 红色：超限，需管控
  │  └─ 预测值 ≥ 容量 120% → 紧急：立即管控
  │
  ▼
⑥ 管控建议生成 ────────────────────────────────────────────
  │  根据告警等级生成管控建议：
  │  ├─ 黄色 → 提示关注，建议加强监测
  │  ├─ 红色 → 建议措施：
  │  │    · 启动限流预案（收费站控制发卡速率）
  │  │    · 引导车辆绕行替代路线
  │  │    · 发布出行提示（情报板/APP）
  │  └─ 紧急 → 立即措施：
  │       · 紧急限流，关闭部分入口
  │       · 联动交警分流
  │       · 开放应急车道
  │       · 启动应急指挥
  │
  ▼
⑦ 结果组织与返回 ──────────────────────────────────────────
  │  组装结构化响应：
  │  - 预测结果（点估计 + 置信区间）
  │  - 告警等级（绿/黄/红/紧急）
  │  - 管控建议（自然语言 + 结构化措施列表）
  │  - 可视化数据（流量趋势折线 + 容量阈值线）
  │  - 审计 ID（可追溯）
```

### 4.2 关键决策点

| 决策点 | 逻辑 | 依赖 |
|--------|------|------|
| 预测方法选择 | 数据量 ≥ 48 → L2 Holt-Winters；否则 L1 移动平均 | traffic-forecast-qa Skill 内部 |
| 告警等级判定 | 预测值 vs 路段容量阈值（80%/100%/120%） | db-connector 查询 road_segment_capacity 表 |
| 管控建议生成 | 按告警等级匹配预案库 | 预案规则（本方案内置） |
| 是否需分流 | 红色及以上告警 + 查询替代路线 | db-connector 查询路网拓扑 |

### 4.3 多轮对话示例

```
用户：预测某高速某收费站明天早高峰车流
Agent：请问您关注的是上行（A 方向）还是下行（B 方向）？
用户：上行
Agent：好的，正在查询某高速某收费站上行历史车流数据并预测...

  预测结果：
  - 明日 7:00-9:00 上行预测车流约 12,500 辆
  - 7:30-8:30 为高峰时段，预计 4,800 辆
  - 置信区间：[11,200, 13,800]

  容量判定：
  - 某收费站上行早高峰容量阈值：15,000 辆/小时
  - 当前预测占容量 83% → 黄色预警

  管控建议：
  - 建议关注 7:30-8:30 时段车流变化
  - 可预备收费站限流预案，视实际车流决定是否启动
  - 建议情报板发布出行提示，引导错峰出行

用户：如果超过容量怎么办？
Agent：若实际车流达到或超过 15,000 辆（容量 100%），将触发红色告警，建议措施：
  1. 启动某收费站限流，控制发卡速率
  2. 引导车辆绕行某高速支线或某平行高速路段
  3. 情报板与某高速 APP 发布分流提示
  4. 联动高速交警加强巡逻与疏导
```

## 5. 数据依赖

### 5.1 所需业务表（客户已有，通过 db-connector.import_schema() 导入）

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `highway_traffic_flow` | 某高速车流历史数据 | road_code, station_code, stat_time, vehicle_count, direction, granularity |
| `highway_toll_station` | 某高速收费站信息 | station_code, name, road_code, direction, lane_count |
| `road_segment_capacity` | 路段容量阈值 | road_code, direction, time_slot, max_capacity, warning_threshold |
| `speed_restriction` | 限速信息 | road_code, start_km, end_km, restriction_type, start_time, end_time |

### 5.2 容量阈值规则

| 等级 | 占容量比 | 颜色 | 处置 |
|------|----------|------|------|
| 正常 | < 80% | 绿色 | 正常运行 |
| 预警 | 80% - 100% | 黄色 | 关注 + 预备 |
| 超限 | 100% - 120% | 红色 | 启动管控 |
| 紧急 | ≥ 120% | 红色紧急 | 立即处置 |

## 6. 部署步骤

### 6.1 安装依赖

```bash
cd workshop
# 安装 db-connector 工具
pip install -r tools/db-connector/requirements.txt
pip install -e tools/db-connector

# 安装 traffic-forecast-qa Skill
pip install -r skills/traffic-forecast-qa/requirements.txt
pip install -e skills/traffic-forecast-qa
```

### 6.2 配置数据库连接

```bash
# 设置环境变量（凭证不落盘）
export DB_HOST=your-db-host
export DB_PORT=3306
export DB_NAME=highway_business_db
export DB_USER=highway_agent
export DB_PASSWORD=your-password

# 或使用 Vault
export VAULT_ADDR=https://vault.example.com
# config.yaml 中 credential.provider 设为 vault
```

### 6.3 启动 MCP 服务（可选，若 Agent 运行时通过 MCP 调用）

```bash
cd workshop
./start.sh
```

### 6.4 Agent 集成

Agent 平台按以下顺序加载：

1. 通过 MCP 或直接 import 加载 `db-connector` 工具
2. 调用 `import_schema()` 导入公路业务表结构
3. 加载 `traffic-forecast-qa` Skill，传入 db-connector 实例和公路字段映射配置
4. 在 Agent 工作流中编排：意图识别 → Skill 预测 → 阈值判定 → 管控建议

## 7. 与其他子行业的关系

本方案虽放在 `highway/` 下，但核心资产（db-connector、traffic-forecast-qa）为跨子行业共享：

- **铁路**：同样可用 traffic-forecast-qa 预测客流，阈值表换为 line_capacity
- **航空**：可预测机场旅客流量，阈值表换为 airport_capacity
- **港口**：可预测港口吞吐量，阈值表换为 port_capacity

各子行业只需调整 `data-mapping.yml` 中的表名/列名映射与容量阈值规则，无需修改底层工具与 Skill 代码。

## 8. 后续演进

| 阶段 | 能力 | 说明 |
|------|------|------|
| v0.1（当前） | 流量预测 + 阈值告警 + 管控建议 | 基于 L1/L2 统计预测 + 规则引擎 |
| v0.2 | 多路段协同管控 | 考虑路网拓扑，跨路段分流建议 |
| v0.3 | 实时管控闭环 | 接入实时车流，动态调整预测与告警 |
| v1.0 | ML 预测 + 智能决策 | 引入 L3 时序模型，强化学习优化管控策略 |

## 9. 贡献说明

遵循仓库 [贡献指南](../../../CONTRIBUTING.md) 提交 PR。
